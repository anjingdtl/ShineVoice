package com.shinevoice.provider.sherpa

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
            return failure(request, elapsed(startedAt), TtsErrorCode.EmptyText, "请输入需要生成的中文文本。")
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
            val audio = runtimeManager.generate(request)
            if (audio.samples.isEmpty() || audio.sampleRate <= 0) {
                failure(request, elapsed(startedAt), TtsErrorCode.NativeRuntimeError, "Native Runtime 未返回有效音频。")
            } else {
                val output = wavStorage.generatedFile(request.taskId)
                if (!audio.save(output.absolutePath)) {
                    failure(request, elapsed(startedAt), TtsErrorCode.StorageError, "生成 WAV 保存失败。")
                } else {
                    val audioDurationMs = audio.samples.size.toLong() * 1000L / audio.sampleRate
                    val elapsedMs = elapsed(startedAt)
                    logger.i(
                        "ZipVoice generated task=${request.taskId} textLength=${request.text.length} " +
                            "elapsedMs=$elapsedMs audioDurationMs=$audioDurationMs sampleRate=${audio.sampleRate}",
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
