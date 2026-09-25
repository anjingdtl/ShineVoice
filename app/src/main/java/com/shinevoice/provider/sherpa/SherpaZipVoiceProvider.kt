package com.shinevoice.provider.sherpa

import com.shinevoice.core.audio.AudioSpeedScaler
import com.shinevoice.core.audio.WavDurationReader
import com.shinevoice.core.log.AppLogger
import com.shinevoice.core.model.AudioFormat
import com.shinevoice.core.storage.ModelDirectoryResolver
import com.shinevoice.core.storage.WavStorage
import com.shinevoice.domain.tts.ProviderResult
import com.shinevoice.domain.tts.TtsCapabilities
import com.shinevoice.domain.tts.TtsError
import com.shinevoice.domain.tts.TtsErrorCode
import com.shinevoice.domain.tts.TtsProvider
import com.shinevoice.domain.tts.TtsRequest
import com.shinevoice.domain.tts.TtsResult
import com.shinevoice.domain.tts.TtsVoice
import com.shinevoice.domain.tts.TtsLanguageCatalog
import com.k2fsa.sherpa.onnx.GeneratedAudio
import java.io.File
import kotlinx.coroutines.CancellationException

class SherpaZipVoiceProvider(
    private val modelResolver: ModelDirectoryResolver,
    private val runtimeManager: SherpaRuntimeManager,
    private val wavStorage: WavStorage,
    private val logger: AppLogger,
) : TtsProvider {
    override val id: String = PROVIDER_ID
    override val displayName: String = "本地 ZipVoice"

    override suspend fun initialize(): ProviderResult = runtimeManager.initialize()

    override suspend fun getCapabilities(): TtsCapabilities = TtsCapabilities(
        supportsVoiceClone = true,
        supportsOffline = true,
        supportsStreaming = false,
        supportsSpeed = true,
        supportsPitch = false,
        supportsEmotion = false,
        supportsFileOutput = true,
        supportedFormats = setOf(AudioFormat.WAV_PCM_16),
        supportedLanguages = setOf(TtsLanguageCatalog.ZH_CN.id, TtsLanguageCatalog.EN_US.id),
        supportsAutoLanguage = false,
        minSpeed = 0.5f,
        maxSpeed = 2.0f,
        defaultSpeed = 1.0f,
    )

    override suspend fun getVoices(): List<TtsVoice> = listOf(
        TtsVoice(DEFAULT_VOICE_ID, "默认参考音色", "zh-CN"),
    )

    override suspend fun validateConfig(): ProviderResult {
        // Model-only check: the default reference.wav and referenceText are
        // validated by the VoiceProfile layer, not by the model status.
        val status = modelResolver.inspect()
        return if (status.ready) {
            ProviderResult.ok(status.summary)
        } else {
            ProviderResult.failure(
                TtsError(
                    TtsErrorCode.ModelNotInstalled,
                    status.summary,
                    "root=${status.rootPath}",
                ),
            )
        }
    }

    /** Validates a specific VoiceProfile's reference inputs before synthesis. */
    fun validateReference(referenceAudioPath: String?, referenceText: String): ProviderResult {
        val audio = referenceAudioPath?.let { File(it) }
        if (audio == null || !audio.isFile) {
            return ProviderResult.failure(
                TtsError(
                    TtsErrorCode.InvalidReferenceAudio,
                    "参考音频缺失，请先在音色库中为当前音色准备录音或导入音频。",
                    "path=$referenceAudioPath",
                ),
            )
        }
        if (referenceText.isBlank()) {
            return ProviderResult.failure(
                TtsError(
                    TtsErrorCode.InvalidReferenceText,
                    "参考文本（referenceText）为空，请填写与参考音频匹配的文字。",
                ),
            )
        }
        return ProviderResult.ok()
    }

    override suspend fun synthesize(request: TtsRequest): TtsResult {
        val startedAt = System.nanoTime()
        if (request.outputFormat != AudioFormat.WAV_PCM_16) {
            return failure(request, elapsed(startedAt), TtsErrorCode.UnsupportedFormat, "Phase 1 只输出 WAV PCM 16-bit。")
        }
        if (request.text.isBlank()) {
            return failure(request, elapsed(startedAt), TtsErrorCode.EmptyText, "请输入需要生成的文字。")
        }
        if (request.language != null && request.language !in getCapabilities().supportedLanguages) {
            return failure(request, elapsed(startedAt), TtsErrorCode.UnsupportedLanguage, "本地模型暂不支持所选语言。")
        }
        val referenceText = request.extra[EXTRA_REFERENCE_TEXT].orEmpty()
        if (referenceText.isBlank()) {
            return failure(request, elapsed(startedAt), TtsErrorCode.InvalidReferenceText, "referenceText 不能为空。")
        }
        val validation = validateConfig()
        if (!validation.success) return failure(
            request,
            elapsed(startedAt),
            validation.error?.code ?: TtsErrorCode.ModelNotInstalled,
            validation.message,
            validation.error?.causeMessage,
        )
        val initialization = runtimeManager.initialize()
        if (!initialization.success) return failure(
            request,
            elapsed(startedAt),
            initialization.error?.code ?: TtsErrorCode.NativeRuntimeError,
            initialization.message,
            initialization.error?.causeMessage,
        )

        return try {
            // sherpa-onnx ZipVoice can under-allocate its speech-condition
            // buffer when a fast request makes generated frames shorter than
            // the reference prompt. Generate fast requests at the safe 1.0x
            // native setting, then transform the copied PCM in Kotlin. This
            // keeps the request's observable speed and duration while avoiding
            // an uncaught native buffer overflow in the bundled AAR.
            val nativeSpeed = if (request.speed > 1.0f) 1.0f else request.speed
            val generated = runtimeManager.generate(request.copy(speed = nativeSpeed))
            val audio = if (nativeSpeed == request.speed) {
                generated
            } else {
                GeneratedAudio(AudioSpeedScaler.scale(generated.samples, request.speed), generated.sampleRate)
            }
            if (audio.samples.isEmpty() || audio.sampleRate <= 0) {
                failure(request, elapsed(startedAt), TtsErrorCode.NativeRuntimeError, "Native Runtime 未返回有效音频。")
            } else if (audio.samples.any { !it.isFinite() }) {
                // NaN/Inf must never reach storage; the native clamped writer
                // would turn them into garbage instead of failing loudly.
                logger.e("ZipVoice output contained non-finite samples task=${request.taskId}")
                failure(request, elapsed(startedAt), TtsErrorCode.NativeRuntimeError, "本地生成输出异常（含非有限值），已丢弃。")
            } else {
                val output = wavStorage.generatedFile(request.taskId)
                if (!audio.save(output.absolutePath)) {
                    failure(request, elapsed(startedAt), TtsErrorCode.StorageError, "生成 WAV 保存失败。")
                } else {
                    // Reload-and-verify: a success history entry requires a
                    // parsable file whose duration matches the returned audio,
                    // not merely that save() returned true.
                    val expectedMs = audio.samples.size.toLong() * 1000L / audio.sampleRate
                    val rereadMs = WavDurationReader.durationMs(output)
                    if (rereadMs == null || rereadMs <= 0L ||
                        rereadMs < expectedMs / 2 || rereadMs > expectedMs * 2
                    ) {
                        output.delete()
                        logger.e(
                            "ZipVoice reload verification failed task=${request.taskId} " +
                                "expectedMs=$expectedMs rereadMs=$rereadMs",
                        )
                        failure(
                            request,
                            elapsed(startedAt),
                            TtsErrorCode.StorageError,
                            "生成文件校验失败（时长不符），已丢弃，不会记录为成功。",
                        )
                    } else {
                        val audioDurationMs = expectedMs
                        val elapsedMs = elapsed(startedAt)
                        logger.i(
                            "ZipVoice generated task=${request.taskId} textLength=${request.text.length} " +
                                "nativeSpeed=$nativeSpeed requestedSpeed=${request.speed} " +
                                "elapsedMs=$elapsedMs audioDurationMs=$audioDurationMs rereadMs=$rereadMs " +
                                "sampleRate=${audio.sampleRate}",
                        )
                        TtsResult(
                            taskId = request.taskId,
                            providerId = id,
                            success = true,
                            audioFile = output.absolutePath,
                            durationMs = audioDurationMs,
                            sampleRate = audio.sampleRate,
                            model = ModelDirectoryResolver.ZIPVOICE_MODEL_ID,
                            voiceId = request.voiceId ?: DEFAULT_VOICE_ID,
                            elapsedMs = elapsedMs,
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            logger.e("ZipVoice synthesis failed", throwable)
            val errorCode = when (throwable) {
                is IllegalArgumentException -> TtsErrorCode.InvalidReferenceAudio
                else -> TtsErrorCode.NativeRuntimeError
            }
            failure(request, elapsed(startedAt), errorCode, "本地 ZipVoice 生成失败。", throwable.message)
        }
    }

    override suspend fun cancel(taskId: String) {
        // The official synchronous OfflineTts API has no cancellation handle.
        // TtsManager serializes tasks; future async Native support can be added here.
        logger.i("ZipVoice cancel requested task=$taskId; synchronous call will finish")
    }

    override suspend fun release() = runtimeManager.release()

    private fun failure(
        request: TtsRequest,
        elapsedMs: Long,
        code: TtsErrorCode,
        message: String,
        cause: String? = null,
    ): TtsResult = TtsResult.failure(
        taskId = request.taskId,
        providerId = id,
        elapsedMs = elapsedMs,
        error = TtsError(code, message, cause),
    )

    private fun elapsed(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    companion object {
        const val PROVIDER_ID = "zipvoice_local"
        const val DEFAULT_VOICE_ID = "default_reference"
        const val EXTRA_REFERENCE_AUDIO = "referenceAudioPath"
        const val EXTRA_REFERENCE_TEXT = "referenceText"
        const val EXTRA_NUM_STEPS = "numSteps"
    }
}
