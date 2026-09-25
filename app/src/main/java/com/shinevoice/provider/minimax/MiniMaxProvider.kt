package com.shinevoice.provider.minimax

import com.shinevoice.core.log.AppLogger
import com.shinevoice.core.model.AudioFormat
import com.shinevoice.core.audio.WavDurationReader
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
import com.shinevoice.domain.tts.TtsLanguageCatalog
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * MiniMax cloud TTS with BYOK. The API key stays in the encrypted config and is
 * never written to logs or source control. UI label: 云端高清.
 *
 * Connection capability is layered and never conflated:
 *  - [validateConfig] verifies the *system voice* catalog — every account with
 *    speech access can list official voices, with or without cloning rights.
 *  - [voiceCatalog] returns system + cloned voices separately; a cloned-list
 *    failure does not invalidate the official voices that already work.
 *  - synthesis accepts any official voice_id from the catalog, so a first-time
 *    user can generate speech without paying for a voice clone.
 */
class MiniMaxProvider(
    private val config: MiniMaxConfig,
    private val apiClient: MiniMaxApiClient,
    private val wavStorage: WavStorage,
    private val logger: AppLogger,
) : TtsProvider, VoiceCloneProvider {
    override val id: String = PROVIDER_ID
    override val displayName: String = "云端高清"

    /** Resolves the current credentials as one atomic snapshot. */
    private suspend fun connection(): MiniMaxConnection? {
        val snapshot = config.snapshot()
        val key = snapshot.apiKey?.takeIf { it.isNotBlank() } ?: return null
        return MiniMaxConnection(key, snapshot.baseUrl, snapshot.groupId)
    }

    override suspend fun initialize(): ProviderResult {
        val snapshot = config.snapshot()
        return if (snapshot.isComplete) {
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
        supportedLanguages = MiniMaxLanguageMapper.supportedLanguageIds,
        supportsAutoLanguage = true,
        minSpeed = 0.5f,
        maxSpeed = 2.0f,
        defaultSpeed = 1.0f,
    )

    /**
     * Voices for pickers: cloned bindings first (they are "your" voices), then
     * official system voices. Failures surface through [voiceCatalog]; this
     * convenience list simply degrades to empty for legacy callers.
     */
    override suspend fun getVoices(): List<TtsVoice> {
        val catalog = voiceCatalog().getOrNull() ?: return emptyList()
        return catalog.clonedVoices.map {
            TtsVoice(id = it.voiceId, displayName = "克隆 · ${it.displayName}", language = null)
        } + catalog.systemVoices.map {
            TtsVoice(id = it.voiceId, displayName = "官方 · ${it.displayName}", language = null)
        }
    }

    /**
     * Loads system + cloned catalogs. The system catalog is the connectivity
     * probe (available to every speech-enabled account); cloned queries run as
     * an independent capability and their failure is reported in
     * [MiniMaxVoiceCatalog.clonedError] without failing the whole call.
     */
    suspend fun voiceCatalog(): Result<MiniMaxVoiceCatalog> {
        val conn = connection() ?: return Result.failure(
            MiniMaxException(
                TtsError(TtsErrorCode.ProviderNotInitialized, "尚未配置云端服务，请在设置中填写 API Key。"),
            ),
        )
        val system = apiClient.listVoices(conn, MiniMaxApiClient.VOICE_TYPE_SYSTEM)
        val systemCatalog = system.getOrElse { error ->
            return Result.failure(error)
        }
        val cloned = apiClient.listVoices(conn, MiniMaxApiClient.VOICE_TYPE_CLONED)
        val clonedCatalog = cloned.getOrNull()
        return Result.success(
            MiniMaxVoiceCatalog(
                systemVoices = systemCatalog.systemVoices,
                clonedVoices = clonedCatalog?.clonedVoices ?: emptyList(),
                clonedError = cloned.exceptionOrNull()?.let { (it as? MiniMaxException)?.error },
            ),
        )
    }

    /** System-voice catalog check: official voices prove the connection + auth. */
    override suspend fun validateConfig(): ProviderResult {
        val conn = connection() ?: run {
            return ProviderResult.failure(
                TtsError(
                    TtsErrorCode.ProviderNotInitialized,
                    "尚未配置云端服务，请在设置中填写 API Key。",
                ),
            )
        }
        return apiClient.listVoices(conn, MiniMaxApiClient.VOICE_TYPE_SYSTEM)
            .fold(
                onSuccess = { ProviderResult.ok("云端连接正常（官方音色 ${it.systemVoices.size} 个）") },
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
        val conn = connection() ?: return failure(
            request,
            startedAt,
            TtsErrorCode.ProviderNotInitialized,
            "尚未配置云端服务，请在设置中填写 API Key。",
        )
        if (request.text.isBlank()) {
            return failure(request, startedAt, TtsErrorCode.EmptyText, "请输入需要生成的文字。")
        }
        val languageId = request.language?.let(TtsLanguageCatalog::normalize)
        if (languageId != null && languageId !in MiniMaxLanguageMapper.supportedLanguageIds) {
            return failure(request, startedAt, TtsErrorCode.UnsupportedLanguage, "云端暂不支持所选语言。")
        }
        val voiceId = request.voiceId?.takeIf { it.isNotBlank() }
            ?: config.defaultVoiceId.first()
            ?: return failure(
                request,
                startedAt,
                TtsErrorCode.Unknown,
                "尚未选择云端音色，请在设置或音色库中选择官方音色或克隆音色。",
            )
        val output = wavStorage.generatedFile(request.taskId)
        return apiClient.synthesizeToFile(
            connection = conn,
            voiceId = voiceId,
            text = request.text,
            speed = request.speed,
            languageBoost = MiniMaxLanguageMapper.toLanguageBoost(languageId),
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
        val conn = connection() ?: run {
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
        val upload = apiClient.uploadReferenceAudio(conn, audio)
        val fileId = upload.getOrElse { error ->
            return VoiceCloneResult(
                success = false,
                error = (error as? MiniMaxException)?.error ?: TtsError(TtsErrorCode.ApiServerError, "参考音频上传失败。"),
            )
        }
        val requestedVoiceId = generateVoiceId()
        return try {
            apiClient.cloneVoice(conn, fileId, requestedVoiceId)
                .fold(
                    onSuccess = { echoed ->
                        // The freshly cloned voice is not yet visible in the
                        // get_voice catalog until its first successful
                        // synthesis, so the id must be persisted locally.
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    override suspend fun deleteRemoteVoice(voiceId: String): ProviderResult {
        return ProviderResult.failure(
            TtsError(
                TtsErrorCode.ApiServerError,
                "云端音色删除接口尚未在当前原型中路由，请在云端控制台删除。",
            ),
        )
    }

    override suspend fun cancel(taskId: String) {
        // Requests run through a cancellable OkHttp call bridge; cancelling the
        // launching coroutine aborts the in-flight HTTP request.
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
    private fun wavDurationMs(file: File): Long? = WavDurationReader.durationMs(file)

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
