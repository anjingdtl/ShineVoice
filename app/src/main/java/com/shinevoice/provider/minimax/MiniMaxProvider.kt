package com.shinevoice.provider.minimax

import com.shinevoice.core.log.AppLogger
import com.shinevoice.core.model.AudioFormat
import com.shinevoice.core.storage.WavStorage
import com.shinevoice.data.settings.MiniMaxConfig
import com.shinevoice.domain.tts.ProviderResult
import com.shinevoice.domain.tts.TtsCapabilities
import com.shinevoice.domain.tts.TtsError
import com.shinevoice.domain.tts.TtsErrorCode
import com.shinevoice.domain.tts.TtsProvider
import com.shinevoice.domain.tts.TtsRequest
import com.shinevoice.domain.tts.TtsResult
import com.shinevoice.domain.tts.TtsVoice
import com.shinevoice.domain.tts.VoiceCloneProvider
import com.shinevoice.domain.tts.VoiceCloneRequest
import com.shinevoice.domain.tts.VoiceCloneResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first

/**
 * MiniMax cloud TTS with BYOK. The API key stays in the encrypted config and is
 * never written to logs or source control. UI label: 云端高清.
 */
class MiniMaxProvider(
    private val config: MiniMaxConfig,
    private val apiClient: MiniMaxApiClient,
    private val wavStorage: WavStorage,
    private val logger: AppLogger,
) : TtsProvider, VoiceCloneProvider {
    override val id: String = PROVIDER_ID
    override val displayName: String = "云端高清"

    override suspend fun initialize(): ProviderResult {
        val key = config.apiKey()
        val groupId = config.groupId.first()
        return if (!key.isNullOrBlank() && !groupId.isNullOrBlank()) {
            ProviderResult.ok("云端高清已就绪")
        } else {
            ProviderResult.failure(
                TtsError(
                    TtsErrorCode.ProviderNotInitialized,
                    "尚未配置云端服务，请在设置中填写 API Key。",
                    "groupId=${groupId.isNullOrBlank()}",
                ),
            )
        }
    }

    override suspend fun getCapabilities(): TtsCapabilities = TtsCapabilities(
        supportsVoiceClone = true,
        supportsOffline = false,
        supportsStreaming = false,
        supportsSpeed = true,
        supportsPitch = true,
        supportsEmotion = false,
        supportsFileOutput = true,
        supportedFormats = setOf(AudioFormat.WAV_PCM_16),
    )

    override suspend fun getVoices(): List<TtsVoice> {
        val key = config.apiKey() ?: return emptyList()
        val groupId = config.groupId.first() ?: return emptyList()
        return apiClient.listVoices(key, groupId)
            .getOrDefault(emptyList())
            .map { TtsVoice(id = it.voiceId, displayName = it.name, language = "zh-CN") }
    }

    override suspend fun validateConfig(): ProviderResult {
        val key = config.apiKey()
        val groupId = config.groupId.first()
        if (key.isNullOrBlank() || groupId.isNullOrBlank()) {
            return ProviderResult.failure(
                TtsError(
                    TtsErrorCode.ProviderNotInitialized,
                    "尚未配置云端服务，请在设置中填写 API Key。",
                ),
            )
        }
        return when (val result = apiClient.testConnection(key, groupId)) {
            is Result.Success -> ProviderResult.ok("云端连接正常")
            is Result.Failure -> ProviderResult.failure(
                (result.exceptionOrNull() as? MiniMaxException)?.error
                    ?: TtsError(TtsErrorCode.Unknown, "云端连接测试失败。"),
            )
        }
    }

    override suspend fun synthesize(request: TtsRequest): TtsResult {
        val startedAt = System.nanoTime()
        val key = config.apiKey()
        val groupId = config.groupId.first()
        if (key.isNullOrBlank() || groupId.isNullOrBlank()) {
            return failure(request, startedAt, TtsErrorCode.ProviderNotInitialized, "尚未配置云端服务，请在设置中填写 API Key。")
        }
        if (request.text.isBlank()) {
            return failure(request, startedAt, TtsErrorCode.EmptyText, "请输入需要生成的中文文本。")
        }
        val voiceId = request.voiceId?.takeIf { it.isNotBlank() }
            ?: config.defaultVoiceId.first()
            ?: return failure(request, startedAt, TtsErrorCode.Unknown, "尚未创建云端音色，请先在音色库中克隆云端音色。")
        val output = wavStorage.generatedFile(request.taskId)
        return try {
            apiClient.synthesizeToFile(key, groupId, voiceId, request.text, request.speed, output)
                .fold(
                    onSuccess = {
                        logger.i("MiniMax generated task=${request.taskId} textLength=${request.text.length}")
                        TtsResult(
                            taskId = request.taskId,
                            providerId = id,
                            success = true,
                            audioFile = output.absolutePath,
                            durationMs = null,
                            model = "speech-02-hd",
                            voiceId = voiceId,
                            elapsedMs = elapsedSince(startedAt),
                        )
                    },
                    onFailure = { error ->
                        failure(request, startedAt, (error as? MiniMaxException)?.error ?: TtsError(TtsErrorCode.ApiServerError, "云端生成失败。"))
                    },
                )
        } catch (throwable: Throwable) {
            failure(request, startedAt, TtsErrorCode.NetworkUnavailable, "云端生成失败（网络）。", throwable.message)
        }
    }

    override suspend fun cloneVoice(request: VoiceCloneRequest): VoiceCloneResult {
        val key = config.apiKey()
        val groupId = config.groupId.first()
        if (key.isNullOrBlank() || groupId.isNullOrBlank()) {
            return VoiceCloneResult(
                success = false,
                error = TtsError(TtsErrorCode.ProviderNotInitialized, "尚未配置云端服务，请在设置中填写 API Key。"),
            )
        }
        val audio = File(request.referenceAudioPath)
        if (!audio.isFile) {
            return VoiceCloneResult(
                success = false,
                error = TtsError(TtsErrorCode.InvalidReferenceAudio, "参考音频不存在，请先为该音色准备参考音频。"),
            )
        }
        val newVoiceId = UUID.randomUUID().toString()
        return try {
            apiClient.cloneVoice(key, groupId, audio, newVoiceId)
                .fold(
                    onSuccess = { voiceId ->
                        config.saveDefaultVoiceId(voiceId)
                        logger.i("MiniMax voice cloned voiceId=$voiceId")
                        VoiceCloneResult(success = true, voiceId = voiceId)
                    },
                    onFailure = { error ->
                        VoiceCloneResult(
                            success = false,
                            error = (error as? MiniMaxException)?.error ?: TtsError(TtsErrorCode.ApiServerError, "云端克隆失败。"),
                        )
                    },
                )
        } catch (throwable: Throwable) {
            VoiceCloneResult(
                success = false,
                error = TtsError(TtsErrorCode.NetworkUnavailable, "云端克隆失败（网络）。", throwable.message),
            )
        }
    }

    override suspend fun deleteRemoteVoice(voiceId: String): ProviderResult {
        val key = config.apiKey()
        val groupId = config.groupId.first()
        if (key.isNullOrBlank() || groupId.isNullOrBlank()) {
            return ProviderResult.failure(
                TtsError(TtsErrorCode.ProviderNotInitialized, "尚未配置云端服务。"),
            )
        }
        return ProviderResult.failure(
            TtsError(
                TtsErrorCode.ApiServerError,
                "云端音色删除接口尚未在当前原型中路由，请在云端控制台删除。",
            ),
        )
    }

    override suspend fun cancel(taskId: String) {
        // MiniMax calls are short-lived and serialized by TtsManager; the HTTP
        // client call is cancelled when the launching coroutine is cancelled.
    }

    override suspend fun release() = Unit

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
        const val PROVIDER_ID = "minimax"
    }
}