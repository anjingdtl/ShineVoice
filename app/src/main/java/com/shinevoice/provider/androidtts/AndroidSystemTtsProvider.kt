package com.shinevoice.provider.androidtts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice as AndroidVoice
import com.shinevoice.core.log.AppLogger
import com.shinevoice.core.audio.WavDurationReader
import com.shinevoice.core.model.AudioFormat
import com.shinevoice.core.storage.WavStorage
import com.shinevoice.data.settings.SettingsStore
import com.shinevoice.domain.tts.ProviderResult
import com.shinevoice.domain.tts.TtsCapabilities
import com.shinevoice.domain.tts.TtsError
import com.shinevoice.domain.tts.TtsErrorCode
import com.shinevoice.domain.tts.TtsLanguageCatalog
import com.shinevoice.domain.tts.TtsProvider
import com.shinevoice.domain.tts.TtsRequest
import com.shinevoice.domain.tts.TtsResult
import com.shinevoice.domain.tts.TtsVoice
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** A TTS engine installed on the device (system default or vendor engine). */
data class SystemEngineInfo(
    val packageName: String,
    val label: String,
    val isSystemDefault: Boolean,
)

/**
 * Android System TTS provider. Enumerates installed engines, lets the user
 * pick one (persisted in SettingsStore), initializes TextToSpeech with the
 * chosen engine, and enumerates that engine's voices by their real Locale.
 * The voice used for synthesis can come from the VoiceProfile's system
 * binding or the best voice for the requested language. UI label: 系统语音.
 */
class AndroidSystemTtsProvider(
    private val context: Context,
    private val wavStorage: WavStorage,
    private val logger: AppLogger,
    private val settingsStore: SettingsStore,
) : TtsProvider {
    override val id: String = PROVIDER_ID
    override val displayName: String = "系统语音"

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var currentEnginePackage: String? = null

    /** Serializes engine (re)creation; TTS init callbacks are async. */
    private val engineMutex = Mutex()

    /** Installed TTS engines; the system default is flagged. */
    fun availableEngines(): List<SystemEngineInfo> = runCatching {
        val pm = context.packageManager
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val defaultEngine = try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.TTS_DEFAULT_SYNTH,
            )
        } catch (error: Exception) {
            null
        }
        pm.queryIntentServices(intent, 0)
            .mapNotNull { info ->
                val service = info.serviceInfo ?: return@mapNotNull null
                SystemEngineInfo(
                    packageName = service.packageName,
                    label = service.loadLabel(pm).toString().ifBlank { service.packageName },
                    isSystemDefault = service.packageName == defaultEngine,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
    }.getOrDefault(emptyList())

    /** The user's persisted engine choice; null means "system default". */
    suspend fun preferredEngine(): String? = settingsStore.systemTtsEngine.first()

    /**
     * The engine's live voice name (initializing the engine if needed); the
     * source of truth for "当前系统语音" when the user has not picked one.
     */
    suspend fun currentVoiceName(): String? {
        if (tts == null) {
            val init = initialize()
            if (!init.success) return null
        }
        return runCatching { tts?.voice?.name }.getOrNull()
    }

    /**
     * The engine package actually serving synthesis right now (initializing
     * the engine if needed). Unlike [currentEnginePackage] this resolves the
     * real engine behind a "system default" choice.
     */
    suspend fun activeEnginePackage(): String? {
        if (tts == null) {
            val init = initialize()
            if (!init.success) return null
        }
        // A null tracked package means the engine was created from the system
        // default choice, so resolve the default engine's real package.
        if (currentEnginePackage != null) return currentEnginePackage
        return runCatching { tts?.defaultEngine }.getOrNull()
    }

    /** Switches (or initializes) the engine; persists the choice. */
    suspend fun switchEngine(enginePackage: String?): ProviderResult = engineMutex.withLock {
        if (currentEnginePackage == enginePackage && tts != null) {
            return@withLock ProviderResult.ok("系统语音已就绪")
        }
        val result = createEngineLocked(enginePackage)
        if (result.success) {
            settingsStore.setSystemTtsEngine(enginePackage)
            logger.i("AndroidTTS engine switched to ${enginePackage ?: "system-default"}")
        }
        result
    }

    override suspend fun initialize(): ProviderResult = engineMutex.withLock {
        if (tts != null) {
            ProviderResult.ok("系统语音已就绪")
        } else {
            createEngineLocked(preferredEngine())
        }
    }

    /** Creates a TextToSpeech bound to [enginePackage] (null = system default). */
    private suspend fun createEngineLocked(enginePackage: String?): ProviderResult =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                var created: TextToSpeech? = null
                var resumed = false
                val client = TextToSpeech(context.applicationContext, { status ->
                    if (resumed || !cont.isActive) {
                        runCatching { created?.shutdown() }
                        return@TextToSpeech
                    }
                    resumed = true
                    if (status == TextToSpeech.SUCCESS) {
                        val ready = created
                        if (ready != null) {
                            tts = ready
                            currentEnginePackage = enginePackage
                            selectDefaultVoice(ready)
                            cont.resume(ProviderResult.ok("系统语音已就绪"))
                        } else {
                            cont.resume(
                                ProviderResult.failure(
                                    TtsError(TtsErrorCode.SystemTtsError, "系统语音初始化异常。"),
                                ),
                            )
                        }
                    } else {
                        runCatching { created?.shutdown() }
                        cont.resume(
                            ProviderResult.failure(
                                TtsError(
                                    TtsErrorCode.SystemTtsError,
                                    "系统语音初始化失败：$status",
                                    "TextToSpeech init status=$status engine=${enginePackage ?: "default"}",
                                ),
                            ),
                        )
                    }
                }, enginePackage)
                created = client
                cont.invokeOnCancellation {
                    runCatching { client.shutdown() }
                }
            }
        }

    private fun selectDefaultVoice(tts: TextToSpeech) {
        runCatching {
            val current = tts.voice
            if (current != null) return@runCatching
            tts.voices.orEmpty().firstOrNull()?.let { tts.voice = it }
        }.onFailure { logger.w("Could not select default system voice", it) }
    }

    /**
     * Capabilities reflect the currently selected engine/voice. Offline support
     * follows the platform Voice flag instead of being hard-coded: a voice that
     * requires a network connection is reported as not offline-capable.
     */
    override suspend fun getCapabilities(): TtsCapabilities {
        val engine = tts ?: run {
            initialize()
            tts
        } ?: return TtsCapabilities(
            supportsVoiceClone = false,
            supportsOffline = false,
            supportsStreaming = false,
            supportsSpeed = true,
            supportsPitch = true,
            supportsEmotion = false,
            supportsFileOutput = true,
            supportedFormats = setOf(AudioFormat.WAV_PCM_16),
            supportedLanguages = emptySet(),
        )
        val selectedVoice = runCatching { engine.voice }.getOrNull()
        val supportsOffline = selectedVoice?.let { !it.isNetworkConnectionRequired } ?: false
        return TtsCapabilities(
            supportsVoiceClone = false,
            supportsOffline = supportsOffline,
            supportsStreaming = false,
            supportsSpeed = true,
            supportsPitch = true,
            supportsEmotion = false,
            supportsFileOutput = true,
            supportedFormats = setOf(AudioFormat.WAV_PCM_16),
            supportedLanguages = availableLanguageIds(engine),
            supportsAutoLanguage = false,
            minSpeed = 0.5f,
            maxSpeed = 2.0f,
            defaultSpeed = 1.0f,
        )
    }

    /**
     * Voices of the current engine (initializing it if needed).
     *
     * Engines like Google expose every voice variant twice (offline `-local`
     * and online `-network` twins, e.g. cmn-cn-x-ccc-local/-network), which
     * renders as near-duplicate technical rows. We surface ONE row per voice
     * variant, preferring the offline build and only falling back to the
     * network twin when the variant has no offline implementation.
     */
    override suspend fun getVoices(): List<TtsVoice> {
        if (tts == null) {
            val init = initialize()
            if (!init.success) return emptyList()
        }
        return runCatching {
            voicesOf(tts)
                .groupBy { "${it.locale.toLanguageTag()}#${voiceVariantCode(it)}" }
                .values
                .mapNotNull { group -> group.minByOrNull { it.isNetworkConnectionRequired } }
                .map { voice ->
                    TtsVoice(
                        id = voice.name,
                        displayName = voiceDisplayName(voice),
                        language = localeToLanguageId(voice.locale),
                    )
                }
                .sortedBy { it.id }
        }.getOrDefault(emptyList())
    }

    /** Returns the current engine's voices for a requested app language. */
    suspend fun getVoicesForLanguage(languageId: String?): List<TtsVoice> {
        val requested = TtsLanguageCatalog.find(languageId) ?: return getVoices()
        return getVoices().filter { voice ->
            TtsLanguageCatalog.sameLanguageFamily(voice.language, requested.id)
        }
    }

    /** Actual language ids represented by the selected engine's voice list. */
    fun availableLanguageIds(engine: TextToSpeech? = tts): Set<String> =
        voicesOf(engine).mapNotNull { localeToLanguageId(it.locale) }.toSet()

    private fun voicesOf(engine: TextToSpeech?): List<AndroidVoice> =
        engine?.voices.orEmpty()
            .sortedBy { it.name }

    /** "cmn-cn-x-ccc-local" -> "ccc"; unparseable names keep their full form. */
    internal fun voiceVariantCode(voice: AndroidVoice): String {
        val name = voice.name.substringAfterLast('#')
        val parts = name.split('-')
        return if (parts.size >= 2) parts[parts.size - 2] else name
    }

    private fun voiceDisplayName(voice: AndroidVoice): String {
        val language = TtsLanguageCatalog.find(localeToLanguageId(voice.locale))?.displayName
            ?: voice.locale.displayLanguage
        val code = voiceVariantCode(voice).uppercase(java.util.Locale.US)
        return if (voice.isNetworkConnectionRequired) {
            "$language $code（联网）"
        } else {
            "$language $code（离线）"
        }
    }

    private fun localeToLanguageId(locale: Locale): String? {
        val tag = locale.toLanguageTag()
        TtsLanguageCatalog.all.firstOrNull { it.id.equals(tag, ignoreCase = true) }?.let { return it.id }
        val family = when (locale.language.lowercase(Locale.US)) {
            "cmn", "zho", "zh" -> TtsLanguageCatalog.ZH_CN.id
            "yue" -> TtsLanguageCatalog.ZH_HK.id
            "eng", "en" -> TtsLanguageCatalog.EN_US.id
            "jpn", "ja" -> TtsLanguageCatalog.JA_JP.id
            "kor", "ko" -> TtsLanguageCatalog.KO_KR.id
            "fra", "fre", "fr" -> TtsLanguageCatalog.FR_FR.id
            "deu", "ger", "de" -> TtsLanguageCatalog.DE_DE.id
            "spa", "es" -> TtsLanguageCatalog.ES_ES.id
            "por", "pt" -> TtsLanguageCatalog.PT_PT.id
            "rus", "ru" -> TtsLanguageCatalog.RU_RU.id
            "ara", "ar" -> TtsLanguageCatalog.AR_SA.id
            "tha", "th" -> TtsLanguageCatalog.TH_TH.id
            "vie", "vi" -> TtsLanguageCatalog.VI_VN.id
            "ind", "id" -> TtsLanguageCatalog.ID_ID.id
            "ita", "it" -> TtsLanguageCatalog.IT_IT.id
            "tur", "tr" -> TtsLanguageCatalog.TR_TR.id
            "nld", "dut", "nl" -> TtsLanguageCatalog.NL_NL.id
            "ukr", "uk" -> TtsLanguageCatalog.UK_UA.id
            "pol", "pl" -> TtsLanguageCatalog.PL_PL.id
            "hin", "hi" -> TtsLanguageCatalog.HI_IN.id
            "bul", "bg" -> TtsLanguageCatalog.BG_BG.id
            "dan", "da" -> TtsLanguageCatalog.DA_DK.id
            "heb", "iw", "he" -> TtsLanguageCatalog.HE_IL.id
            "msa", "may", "ms" -> TtsLanguageCatalog.MS_MY.id
            "fas", "per", "fa" -> TtsLanguageCatalog.FA_IR.id
            "slk", "slo", "sk" -> TtsLanguageCatalog.SK_SK.id
            "swe", "sv" -> TtsLanguageCatalog.SV_SE.id
            "hrv", "hr" -> TtsLanguageCatalog.HR_HR.id
            "fil" -> TtsLanguageCatalog.FIL_PH.id
            "hun", "hu" -> TtsLanguageCatalog.HU_HU.id
            "nob", "nor", "nb" -> TtsLanguageCatalog.NB_NO.id
            "slv", "sl" -> TtsLanguageCatalog.SL_SI.id
            "cat", "ca" -> TtsLanguageCatalog.CA_ES.id
            "nno", "nn" -> TtsLanguageCatalog.NN_NO.id
            "tam", "ta" -> TtsLanguageCatalog.TA_IN.id
            "afr", "af" -> TtsLanguageCatalog.AF_ZA.id
            else -> null
        }
        return family
    }

    private fun selectVoiceForLanguage(
        engine: TextToSpeech,
        languageId: String?,
        preferredVoiceId: String?,
    ): AndroidVoice? {
        val voices = voicesOf(engine)
        if (languageId == null) {
            return preferredVoiceId?.let { id -> voices.firstOrNull { it.name == id } }
                ?: engine.voice
                ?: voices.firstOrNull()
        }
        val exact = voices.filter { localeToLanguageId(it.locale) == languageId }
        val family = exact.ifEmpty {
            voices.filter { TtsLanguageCatalog.sameLanguageFamily(localeToLanguageId(it.locale), languageId) }
        }
        return preferredVoiceId?.let { id -> family.firstOrNull { it.name == id } }
            ?: family.minByOrNull { it.isNetworkConnectionRequired }
    }

    override suspend fun validateConfig(): ProviderResult {
        val engine = tts
        return if (engine == null) {
            ProviderResult.failure(
                TtsError(
                    TtsErrorCode.SystemTtsError,
                    "系统语音尚未初始化，请在设置中检查系统 TTS 引擎。",
                ),
            )
        } else {
            ProviderResult.ok("系统语音已就绪（${currentEnginePackage ?: "系统默认引擎"}）")
        }
    }

    override suspend fun synthesize(request: TtsRequest): TtsResult {
        val startedAt = System.nanoTime()
        // Engine binding: profile-specific engine wins, else the global choice.
        val requestedEngine = request.extra[EXTRA_ENGINE]?.takeIf { it.isNotBlank() }
            ?: preferredEngine()
        if (currentEnginePackage != requestedEngine || tts == null) {
            val switch = switchEngine(requestedEngine)
            if (!switch.success) {
                return failure(request, startedAt, switch.error?.code ?: TtsErrorCode.SystemTtsError, switch.message)
            }
        }
        val current = tts ?: return failure(request, startedAt, TtsErrorCode.SystemTtsError, "系统语音不可用")
        if (request.text.isBlank()) {
            return failure(request, startedAt, TtsErrorCode.EmptyText, "请输入需要朗读的文字。")
        }

        val requestedLanguage = request.language?.let(TtsLanguageCatalog::normalize)
        val selectedVoice = selectVoiceForLanguage(current, requestedLanguage, request.voiceId)
        if (requestedLanguage != null && selectedVoice == null) {
            return failure(request, startedAt, TtsErrorCode.UnsupportedLanguage, "当前系统语音引擎不支持所选语言。")
        }
        selectedVoice?.let { voice ->
            val voiceSelection = runCatching { current.voice = voice }
            if (voiceSelection.isFailure) {
                val error = voiceSelection.exceptionOrNull()
                logger.w("AndroidTTS voice selection failed", error)
                return failure(
                    request,
                    startedAt,
                    TtsErrorCode.SystemTtsError,
                    "系统语音无法切换到所选音色。",
                    error?.message,
                )
            }
        }
        val selectedVoiceId = selectedVoice?.name ?: request.voiceId

        // Android's platform API is the portable contract for these controls.
        // Some engines ignore the legacy Bundle keys, so never report success
        // unless the current engine accepts both values for this request.
        val speed = request.speed.coerceIn(MIN_SPEED, MAX_SPEED)
        val pitch = request.pitch.coerceIn(MIN_PITCH, MAX_PITCH)
        val speechRateStatus = runCatching { current.setSpeechRate(speed) }
            .getOrElse { error ->
                logger.w("AndroidTTS setSpeechRate threw", error)
                TextToSpeech.ERROR
            }
        if (speechRateStatus != TextToSpeech.SUCCESS) {
            return failure(
                request,
                startedAt,
                TtsErrorCode.SystemTtsError,
                "当前系统语音引擎不接受语速设置。",
                "setSpeechRate status=$speechRateStatus speed=$speed",
            )
        }
        val pitchStatus = runCatching { current.setPitch(pitch) }
            .getOrElse { error ->
                logger.w("AndroidTTS setPitch threw", error)
                TextToSpeech.ERROR
            }
        if (pitchStatus != TextToSpeech.SUCCESS) {
            return failure(
                request,
                startedAt,
                TtsErrorCode.SystemTtsError,
                "当前系统语音引擎不接受音调设置。",
                "setPitch status=$pitchStatus pitch=$pitch",
            )
        }
        val output = wavStorage.generatedFile(request.taskId)

        val synthesisResult = suspendCancellableCoroutine<TtsResult> { cont ->
            val listener = object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == request.taskId && cont.isActive) {
                        cont.resume(
                            failure(request, startedAt, TtsErrorCode.SystemTtsError, "系统语音朗读失败。"),
                        )
                    }
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == request.taskId && cont.isActive) {
                        val elapsed = elapsedSince(startedAt)
                        val bytes = runCatching { output.length() }.getOrDefault(0L)
                        logger.i(
                            "AndroidTTS generated task=${request.taskId} elapsedMs=$elapsed bytes=$bytes",
                        )
                        cont.resume(
                            TtsResult(
                                taskId = request.taskId,
                                providerId = id,
                                success = true,
                                audioFile = output.absolutePath,
                                sampleRate = 24000,
                                model = null,
                                voiceId = selectedVoiceId,
                                elapsedMs = elapsed,
                                durationMs = null,
                            ),
                        )
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == request.taskId && cont.isActive) {
                        cont.resume(
                            failure(request, startedAt, TtsErrorCode.SystemTtsError, "系统语音朗读失败（$errorCode）。"),
                        )
                    }
                }
            }
            runCatching {
                current.setOnUtteranceProgressListener(listener)
                // The Bundle is required by the API overload but intentionally
                // carries no rate/pitch keys; those were applied above through
                // setSpeechRate()/setPitch().
                val started = current.synthesizeToFile(request.text, Bundle(), output, request.taskId)
                if (started != TextToSpeech.SUCCESS) {
                    if (cont.isActive) {
                        cont.resume(failure(request, startedAt, TtsErrorCode.SystemTtsError, "系统语音无法开始朗读。"))
                    }
                }
            }.onFailure { throwable ->
                if (cont.isActive) {
                    cont.resume(failure(request, startedAt, TtsErrorCode.SystemTtsError, "系统语音朗读调用失败。", throwable.message))
                }
            }
            cont.invokeOnCancellation {
                runCatching { current.stop() }
            }
        }
        if (!synthesisResult.success || synthesisResult.audioFile == null) {
            return synthesisResult
        }
        val durationMs = withContext(Dispatchers.IO) { wavDurationMs(output) }
        logger.i(
            "AndroidTTS WAV parsed task=${request.taskId} durationMs=$durationMs " +
                "fileBytes=${output.length()}",
        )
        return synthesisResult.copy(durationMs = durationMs)
    }

    override suspend fun cancel(taskId: String) {
        runCatching { tts?.stop() }
        logger.i("AndroidTTS cancel requested task=$taskId")
    }

    override suspend fun release() {
        engineMutex.withLock {
            runCatching { tts?.shutdown() }
            tts = null
            currentEnginePackage = null
        }
    }

    /** TTS engines may signal onDone just before the final WAV header is visible. */
    private fun wavDurationMs(file: java.io.File): Long? {
        // Google TTS can finish the callback before the engine closes/flushed
        // the destination. Keep the wait bounded so a broken engine still
        // returns a normal result instead of hanging the generation forever.
        repeat(40) { attempt ->
            val duration = WavDurationReader.durationMs(file)
            if (duration != null && duration > 0L) return duration
            if (attempt < 39) runCatching { Thread.sleep(50L) }
        }
        return null
    }

    private fun failure(
        request: TtsRequest,
        startedAt: Long,
        code: TtsErrorCode,
        message: String,
        cause: String? = null,
    ): TtsResult = TtsResult.failure(
        taskId = request.taskId,
        providerId = id,
        elapsedMs = elapsedSince(startedAt),
        error = TtsError(code, message, cause),
    )

    private fun elapsedSince(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    companion object {
        const val PROVIDER_ID = "android_system_tts"
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2.0f
        /** Request extra carrying a profile-bound engine package. */
        const val EXTRA_ENGINE = "enginePackage"
    }
}
