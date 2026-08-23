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

/** Phase 0 boundary; Native implementation is added in the Phase 1 commit. */
class SherpaZipVoiceProvider(
    private val modelResolver: ModelDirectoryResolver,
    private val wavStorage: WavStorage,
    private val logger: AppLogger,
) : TtsProvider {
    override val id: String = PROVIDER_ID
    override val displayName: String = "本地 ZipVoice"

    override suspend fun initialize(): ProviderResult = ProviderResult.failure(
        TtsError(
            TtsErrorCode.ProviderNotInitialized,
            "ZipVoice Native Runtime 尚未接入，请准备 Phase 1 模型。",
        ),
    )

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
        val status = modelResolver.inspect()
        return if (status.ready) {
            ProviderResult.failure(
                TtsError(
                    TtsErrorCode.ProviderNotInitialized,
                    "Phase 0 仅建立 Provider 边界，尚未加载 Native Runtime。",
                ),
            )
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

    override suspend fun synthesize(request: TtsRequest): TtsResult = TtsResult.failure(
        taskId = request.taskId,
        providerId = id,
        elapsedMs = 0L,
        error = TtsError(
            TtsErrorCode.ProviderNotInitialized,
            "Phase 0 尚未执行本地推理；Phase 1 将接入官方 sherpa-onnx Runtime。",
        ),
    )

    override suspend fun cancel(taskId: String) {
        logger.i("Phase 0 cancel requested task=$taskId")
    }

    override suspend fun release() {
        logger.i("Phase 0 ZipVoice provider released")
    }

    companion object {
        const val PROVIDER_ID = "zipvoice_local"
        const val DEFAULT_VOICE_ID = "default_reference"
        const val EXTRA_REFERENCE_AUDIO = "referenceAudioPath"
        const val EXTRA_REFERENCE_TEXT = "referenceText"
        const val EXTRA_NUM_STEPS = "numSteps"
    }
}

