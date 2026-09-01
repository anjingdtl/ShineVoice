package com.shinevoice.provider.androidtts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice as AndroidVoice
import com.shinevoice.core.log.AppLogger
import com.shinevoice.core.model.AudioFormat
import com.shinevoice.core.storage.WavStorage
import com.shinevoice.data.settings.SettingsStore
import com.shinevoice.domain.tts.ProviderResult
import com.shinevoice.domain.tts.TtsCapabilities
import com.shinevoice.domain.tts.TtsError
import com.shinevoice.domain.tts.TtsErrorCode
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
 * chosen engine, and enumerates that engine's Chinese voices. The voice used
 * for synthesis can come from the VoiceProfile's system binding or the global
 * default. UI label: 系统语音.
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
                            selectChineseVoice(ready)
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

    private fun selectChineseVoice(tts: TextToSpeech) {
        runCatching {
            val candidates = buildList {
                add(Locale.CHINA)
                add(Locale.CHINESE)
                add(Locale.SIMPLIFIED_CHINESE)
            }
            for (locale in candidates) {
                when (tts.isLanguageAvailable(locale)) {
                    TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE,
                    TextToSpeech.LANG_COUNTRY_AVAILABLE,
                    TextToSpeech.LANG_AVAILABLE,
                    -> {
                        tts.language = locale
                        return
                    }
                }
            }
        }.onFailure { logger.w("Could not select Chinese voice", it) }
    }

    /**
     * Capabilities reflect the currently selected engine/voice. Offline support
     * follows the platform Voice flag instead of being hard-coded: a voice that
     * requires a network connection is reported as not offline-capable.
     */
    override suspend fun getCapabilities(): TtsCapabilities {
        val engine = tts ?: return TtsCapabilities(
            supportsVoiceClone = false,
            supportsOffline = false,
            supportsStreaming = false,
            supportsSpeed = true,
            supportsPitch = true,
            supportsEmotion = false,
            supportsFileOutput = true,
            supportedFormats = setOf(AudioFormat.WAV_PCM_16),
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
        )
    }

    /** Chinese voices of the current engine (initializing it if needed). */
    override suspend fun getVoices(): List<TtsVoice> {
        if (tts == null) {
            val init = initialize()
            if (!init.success) return emptyList()
        }
        return runCatching {
            chineseVoicesOf(tts).map { voice ->
                TtsVoice(
                    id = voice.name,
                    displayName = voiceDisplayName(voice),
                    language = voice.locale.toLanguageTag(),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun chineseVoicesOf(engine: TextToSpeech?): List<AndroidVoice> =
        engine?.voices.orEmpty()
            .filter { voice ->
                val language = voice.locale.language
                language == "zh" || language == "cmn"
            }
            .sortedBy { it.name }

    private fun voiceDisplayName(voice: AndroidVoice): String {
        val base = voice.name.substringAfterLast('#').ifBlank { voice.name }
        val suffix = if (voice.isNetworkConnectionRequired) "（联网）" else ""
        return base + suffix
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
            return failure(request, startedAt, TtsErrorCode.EmptyText, "请输入需要朗读的中文文本。")
        }

        val output = wavStorage.generatedFile(request.taskId)
        val params = Bundle().apply {
            // Constants match android.speech.tts.Engine.KEY_PARAM_PITCH / KEY_PARAM_RATE
            // ("pitch" / "rate"); hard-coded to avoid SDK constant drift across API levels.
            putFloat("pitch", request.pitch.coerceIn(0.5f, 2.0f))
            putFloat("rate", request.speed.coerceIn(0.5f, 2.0f))
        }

        return suspendCancellableCoroutine { cont ->
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
                                voiceId = request.voiceId,
                                elapsedMs = elapsed,
                                durationMs = wavDurationMs(output),
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
                request.voiceId?.let { requested ->
                    current.voices.firstOrNull { it.name == requested }?.let { current.voice = it }
                }
                val started = current.synthesizeToFile(request.text, params, output, request.taskId)
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

    private fun wavDurationMs(file: java.io.File): Long? = runCatching {
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(12)
            var sampleRate = 0
            var channels = 0
            var bitsPerSample = 0
            var dataBytes = 0L
            while (raf.filePointer + 8 <= raf.length()) {
                val chunkHeader = ByteArray(8)
                raf.readFully(chunkHeader)
                val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val chunk = java.nio.ByteBuffer.wrap(chunkHeader, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong()
                when (chunkId) {
                    "fmt " -> {
                        val fmt = ByteArray(chunk.toInt())
                        raf.readFully(fmt)
                        channels = java.nio.ByteBuffer.wrap(fmt, 2, 2).short.toInt() and 0xffff
                        sampleRate = java.nio.ByteBuffer.wrap(fmt, 4, 4).int
                        bitsPerSample = java.nio.ByteBuffer.wrap(fmt, 14, 2).short.toInt() and 0xffff
                    }
                    "data" -> {
                        dataBytes = chunk
                        break
                    }
                    else -> raf.skipBytes(chunk.toInt())
                }
            }
            if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return@runCatching null
            dataBytes * 8000L / (sampleRate.toLong() * channels * (bitsPerSample / 8))
        }
    }.getOrNull()

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
        /** Request extra carrying a profile-bound engine package. */
        const val EXTRA_ENGINE = "enginePackage"
    }
}
