package com.shinevoice.provider.androidtts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.shinevoice.core.log.AppLogger
import com.shinevoice.core.model.AudioFormat
import com.shinevoice.core.storage.WavStorage
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Android System TTS provider. The system engine speaks Chinese with speed and
 * pitch; file output is used when the engine supports synthesizeToFile.
 * UI label: 系统语音.
 */
class AndroidSystemTtsProvider(
    private val context: Context,
    private val wavStorage: WavStorage,
    private val logger: AppLogger,
) : TtsProvider {
    override val id: String = PROVIDER_ID
    override val displayName: String = "系统语音"

    @Volatile private var tts: TextToSpeech? = null

    override suspend fun initialize(): ProviderResult = withContext(Dispatchers.Main) {
        if (tts != null) {
            ProviderResult.ok("系统语音已就绪")
        } else {
            initializeOnMain()
        }
    }

    private suspend fun initializeOnMain(): ProviderResult = suspendCancellableCoroutine { cont ->
        var created: TextToSpeech? = null
        val client = TextToSpeech(context.applicationContext) { status ->
            if (!cont.isActive) {
                runCatching { created?.shutdown() }
                return@TextToSpeech
            }
            if (status == TextToSpeech.SUCCESS) {
                tts = client
                selectChineseVoice(client)
                cont.resume(ProviderResult.ok("系统语音已就绪"))
            } else {
                runCatching { client.shutdown() }
                cont.resume(
                    ProviderResult.failure(
                        TtsError(
                            TtsErrorCode.SystemTtsError,
                            "系统语音初始化失败：$status",
                            "TextToSpeech init status=$status",
                        ),
                    ),
                )
            }
        }
        created = client
        cont.invokeOnCancellation {
            runCatching { client.shutdown() }
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

    override suspend fun getCapabilities(): TtsCapabilities = TtsCapabilities(
        supportsVoiceClone = false,
        supportsOffline = true,
        supportsStreaming = false,
        supportsSpeed = true,
        supportsPitch = true,
        supportsEmotion = false,
        supportsFileOutput = true,
        supportedFormats = setOf(AudioFormat.WAV_PCM_16),
    )

    override suspend fun getVoices(): List<TtsVoice> {
        val engine = tts ?: run {
            initialize()
        }
        if (!engine.success) return emptyList()
        return runCatching {
            (tts?.voices ?: emptyList())
                .filter { voice ->
                    val language = voice.locale.language
                    language == "zh" || language == "cmn"
                }
                .map { voice ->
                    TtsVoice(
                        id = voice.name,
                        displayName = voice.name.ifBlank { voice.locale.displayName },
                        language = voice.locale.toLanguageTag(),
                    )
                }
        }.getOrDefault(emptyList())
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
            ProviderResult.ok("系统语音已就绪")
        }
    }

    override suspend fun synthesize(request: TtsRequest): TtsResult {
        val startedAt = System.nanoTime()
        if (tts == null) {
            val init = initialize()
            if (!init.success) {
                return failure(request, startedAt, init.error?.code ?: TtsErrorCode.SystemTtsError, init.message)
            }
        }
        val current = tts ?: return failure(request, startedAt, TtsErrorCode.SystemTtsError, "系统语音不可用")
        if (request.text.isBlank()) {
            return failure(request, startedAt, TtsErrorCode.EmptyText, "请输入需要朗读的中文文本。")
        }

        val output = wavStorage.generatedFile(request.taskId)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_PITCH, request.pitch.coerceIn(0.5f, 2.0f))
            putFloat(TextToSpeech.Engine.KEY_PARAM_SPEED, request.speed.coerceIn(0.5f, 2.0f))
        }

        return suspendCancellableCoroutine { cont ->
            val listener = object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == request.taskId && cont.isActive) {
                        val elapsed = elapsedSince(startedAt)
                        val duration = runCatching { output.length() }.getOrDefault(0L)
                        logger.i(
                            "AndroidTTS generated task=${request.taskId} elapsedMs=$elapsed bytes=$duration",
                        )
                        cont.resume(
                            TtsResult(
                                taskId = request.taskId,
                                providerId = id,
                                success = true,
                                audioFile = output.absolutePath,
                                sampleRate = null,
                                model = null,
                                voiceId = request.voiceId,
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
        runCatching { tts?.shutdown() }
        tts = null
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
    }
}