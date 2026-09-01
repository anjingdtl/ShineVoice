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
import java.security.SecureRandom
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

    private data class Credentials(val apiKey: String, val baseUrl: String, val groupId: String?)

    private suspend fun credentials(): Credentials? {
        val key = config.apiKey()?.takeIf { it.isNotBlank() } ?: return null
        val groupId = config.groupId.first()?.takeIf { it.isNotBlank() }
        return Credentials(key, config.baseUrl(), groupId)
    }

    override suspend fun initialize(): ProviderResult {
        val cred = credentials()
        return if (cred != null) {
            ProviderResult.ok("云端高清已就绪")
        } else {
            ProviderResult.failure(
                TtsError(
                    TtsErrorCode.ProviderNotInitialized,
                    "尚未配置云端服务，请在设置中填写 API Key。",
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
        val cred = credentials() ?: return emptyList()
        return apiClient.listVoices(cred.apiKey, cred.baseUrl, cred.groupId)
            .getOrDefault(emptyList())
            .map { TtsVoice(id = it.voiceId, displayName = it.name, language = "zh-CN") }
    }

    override suspend fun validateConfig(): ProviderResult {
        val cred = credentials() ?: run {
            return ProviderResult.failure(
                TtsError(
                    TtsErrorCode.ProviderNotInitialized,
                    "尚未配置云端服务，请在设置中填写 API Key。",
                ),
            )
        }
        return apiClient.listVoices(cred.apiKey, cred.baseUrl, cred.groupId)
            .fold(
                onSuccess = { ProviderResult.ok("云端连接正常") },
                onFailure = { error ->
                    ProviderResult.failure(
                        (error as? MiniMaxException)?.error
                            ?: TtsError(TtsErrorCode.Unknown, "云端连接测试失败。"),
                    )
                },
            )
    }

    override suspend fun synthesize(request: TtsRequest): TtsResult {
        val startedAt = System.nanoTime()
        val cred = credentials() ?: return failure(
            request,
            startedAt,
            TtsErrorCode.ProviderNotInitialized,
            "尚未配置云端服务，请在设置中填写 API Key。",
        )
        if (request.text.isBlank()) {
            return failure(request, startedAt, TtsErrorCode.EmptyText, "请输入需要生成的中文文本。")
        }
        val voiceId = request.voiceId?.takeIf { it.isNotBlank() }
            ?: config.defaultVoiceId.first()
            ?: return failure(
                request,
                startedAt,
                TtsErrorCode.Unknown,
                "尚未创建云端音色，请先在音色库中克隆云端音色。",
            )
        val output = wavStorage.generatedFile(request.taskId)
        return apiClient.synthesizeToFile(
            apiKey = cred.apiKey,
            baseUrl = cred.baseUrl,
            groupId = cred.groupId,
            voiceId = voiceId,
            text = request.text,
            speed = request.speed,
            outputFile = output,
        ).fold(
            onSuccess = {
                logger.i(
                    "MiniMax generated task=${request.taskId} textLength=${request.text.length} " +
                        "voiceIdLength=${voiceId.length} bytes=${it.length()}",
                )
                TtsResult(
                    taskId = request.taskId,
                    providerId = id,
                    success = true,
                    audioFile = output.absolutePath,
                    durationMs = wavDurationMs(output),
                    sampleRate = 24000,
                    model = MiniMaxApiClient.DEFAULT_MODEL,
                    voiceId = voiceId,
                    elapsedMs = elapsedSince(startedAt),
                )
            },
            onFailure = { error ->
                val ttsError = (error as? MiniMaxException)?.error
                    ?: TtsError(TtsErrorCode.ApiServerError, "云端生成失败。")
                TtsResult.failure(
                    taskId = request.taskId,
                    providerId = id,
                    elapsedMs = elapsedSince(startedAt),
                    error = ttsError,
                )
            },
        )
    }

    override suspend fun cloneVoice(request: VoiceCloneRequest): VoiceCloneResult {
        val cred = credentials() ?: run {
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
        // Two-step official flow: upload -> file_id, then voice_clone with a
        // locally generated voice_id that satisfies the official format rules.
        val upload = apiClient.uploadReferenceAudio(cred.apiKey, cred.baseUrl, cred.groupId, audio)
        val fileId = upload.getOrElse { error ->
            return VoiceCloneResult(
                success = false,
                error = (error as? MiniMaxException)?.error ?: TtsError(TtsErrorCode.ApiServerError, "参考音频上传失败。"),
            )
        }
        val requestedVoiceId = generateVoiceId()
        return apiClient.cloneVoice(cred.apiKey, cred.baseUrl, cred.groupId, fileId, requestedVoiceId)
            .fold(
                onSuccess = { echoed ->
                    val voiceId = echoed.ifBlank { requestedVoiceId }
                    config.saveDefaultVoiceId(voiceId)
                    logger.i("MiniMax voice cloned voiceIdLength=${voiceId.length} fileId=$fileId")
                    VoiceCloneResult(success = true, voiceId = voiceId)
                },
                onFailure = { error ->
                    VoiceCloneResult(
                        success = false,
                        error = (error as? MiniMaxException)?.error ?: TtsError(TtsErrorCode.ApiServerError, "云端克隆失败。"),
                    )
                },
            )
    }

    override suspend fun deleteRemoteVoice(voiceId: String): ProviderResult {
        val cred = credentials() ?: run {
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

    /** Reads the WAV data chunk size for a duration estimate; null when unparsable. */
    private fun wavDurationMs(file: File): Long? = runCatching {
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

    companion object {
        const val PROVIDER_ID = "minimax"

        private val random = SecureRandom()

        /** "sv" + 16 lowercase hex chars: letter-first, 18 chars, rule-compliant. */
        internal fun generateVoiceId(): String {
            val bytes = ByteArray(8)
            random.nextBytes(bytes)
            return "sv" + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
