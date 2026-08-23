package com.shinevoice.domain.tts

import com.shinevoice.core.model.AudioFormat

enum class TtsErrorCode {
    ProviderNotInitialized,
    ModelNotInstalled,
    ModelCorrupted,
    InvalidReferenceAudio,
    InvalidReferenceText,
    UnsupportedFormat,
    NetworkUnavailable,
    ApiUnauthorized,
    ApiRateLimited,
    ApiServerError,
    GenerationTimeout,
    NativeRuntimeError,
    StorageError,
    EmptyText,
    ProviderNotFound,
    Cancelled,
    Unknown,
}

data class TtsError(
    val code: TtsErrorCode,
    val userMessage: String,
    val causeMessage: String? = null,
)

data class ProviderResult(
    val success: Boolean,
    val message: String = "",
    val error: TtsError? = null,
) {
    companion object {
        fun ok(message: String = "") = ProviderResult(success = true, message = message)

        fun failure(error: TtsError) = ProviderResult(
            success = false,
            message = error.userMessage,
            error = error,
        )
    }
}

data class TtsVoice(
    val id: String,
    val displayName: String,
    val language: String? = null,
)

data class TtsCapabilities(
    val supportsVoiceClone: Boolean,
    val supportsOffline: Boolean,
    val supportsStreaming: Boolean,
    val supportsSpeed: Boolean,
    val supportsPitch: Boolean,
    val supportsEmotion: Boolean,
    val supportsFileOutput: Boolean,
    val supportedFormats: Set<AudioFormat>,
)

data class TtsRequest(
    val taskId: String,
    val text: String,
    val providerId: String,
    val voiceProfileId: String? = null,
    val voiceId: String? = null,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    val emotion: String? = null,
    val outputFormat: AudioFormat = AudioFormat.WAV_PCM_16,
    val extra: Map<String, String> = emptyMap(),
)

data class TtsResult(
    val taskId: String,
    val providerId: String,
    val success: Boolean,
    val audioFile: String? = null,
    val durationMs: Long? = null,
    val sampleRate: Int? = null,
    val model: String? = null,
    val voiceId: String? = null,
    val elapsedMs: Long,
    val error: TtsError? = null,
) {
    val rtf: Double?
        get() = if (durationMs == null || durationMs == 0L) null else elapsedMs.toDouble() / durationMs

    companion object {
        fun failure(
            taskId: String,
            providerId: String,
            elapsedMs: Long,
            error: TtsError,
        ) = TtsResult(
            taskId = taskId,
            providerId = providerId,
            success = false,
            elapsedMs = elapsedMs,
            error = error,
        )
    }
}

data class VoiceCloneRequest(
    val voiceProfileId: String,
    val referenceAudioPath: String,
    val referenceText: String,
)

data class VoiceCloneResult(
    val success: Boolean,
    val voiceId: String? = null,
    val error: TtsError? = null,
)

