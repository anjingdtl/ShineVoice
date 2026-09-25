package com.shinevoice.provider.minimax

import com.shinevoice.core.audio.WavDurationReader
import com.shinevoice.domain.tts.TtsError
import com.shinevoice.domain.tts.TtsErrorCode
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

/**
 * One region+key+groupId combination captured atomically by the caller.
 * A single request always uses one snapshot, never mixed revisions.
 */
data class MiniMaxConnection(
    val apiKey: String,
    val baseUrl: String,
    val groupId: String? = null,
)

/** A voice entry from the official get_voice catalog. */
data class MiniMaxCloudVoice(
    val voiceId: String,
    val displayName: String,
    val isCloned: Boolean,
)

/** get_voice result: system voices and cloned voices are separate capabilities. */
data class MiniMaxVoiceCatalog(
    val systemVoices: List<MiniMaxCloudVoice>,
    val clonedVoices: List<MiniMaxCloudVoice>,
    /** Non-fatal cloned-catalog failure (e.g. no clone permission): official voices still work. */
    val clonedError: TtsError? = null,
)

/** Sanitized request outcome metadata; never contains the API key or raw bodies. */
data class MiniMaxDiagnostics(
    val operation: String,
    val host: String,
    val httpStatus: Int? = null,
    val businessCode: Int? = null,
    val elapsedMs: Long,
) {
    override fun toString(): String =
        "op=$operation host=$host http=$httpStatus biz=$businessCode ${elapsedMs}ms"
}

/**
 * MiniMax voice-clone / T2A client following the current official contract:
 *
 *   POST {base}/v1/files/upload        multipart purpose=voice_clone -> file.file_id
 *   POST {base}/v1/voice_clone         JSON  {file_id, voice_id}     -> base_resp
 *   POST {base}/v1/get_voice           JSON  {voice_type}            -> system_voice / voice_cloning
 *   POST {base}/v1/t2a_v2              JSON  voice_setting/audio_setting -> data.audio (hex or url)
 *
 * voice_type enumerates system | voice_cloning | voice_generation | all; cloned
 * voices appear in the catalog only after their first successful synthesis.
 * t2a_v2 responds with output_format=url (expiring link) or hex-encoded audio
 * in data.audio. The Authorization header is never logged and never forwarded
 * to the download host. Errors are split into transport / HTTP / business /
 * protocol layers so the UI can show actionable diagnostics instead of a
 * generic "connection failed".
 */
class MiniMaxApiClient(
    private val client: OkHttpClient = defaultClient(),
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** Queries one voice_type category; empty lists are a legal success state. */
    suspend fun listVoices(
        connection: MiniMaxConnection,
        voiceType: String = VOICE_TYPE_CLONED,
    ): Result<MiniMaxVoiceCatalog> {
        val request = Request.Builder()
            .url(url(connection, "get_voice"))
            .header("Authorization", "Bearer ${connection.apiKey}")
            .post(
                JSONObject().put("voice_type", voiceType)
                    .toString()
                    .toRequestBody(jsonType),
            )
            .build()
        return executeCall("get_voice:$voiceType", request, MAX_JSON_BYTES) { body, _ ->
            parseVoiceCatalog(body, voiceType)
        }
    }

    /** Uploads reference audio for cloning; returns the official file_id. */
    suspend fun uploadReferenceAudio(
        connection: MiniMaxConnection,
        audioFile: File,
    ): Result<Long> = withContext(Dispatchers.IO) {
        if (!audioFile.isFile) {
            return@withContext Result.failure(
                MiniMaxException(
                    TtsError(TtsErrorCode.InvalidReferenceAudio, "参考音频文件不存在，请先录音或导入。"),
                ),
            )
        }
        if (audioFile.length() > MAX_UPLOAD_BYTES) {
            return@withContext Result.failure(
                MiniMaxException(
                    TtsError(
                        TtsErrorCode.InvalidReferenceAudio,
                        "参考音频过大（上限 20 MB），请截取一段 10 秒以上的干净人声。",
                    ),
                ),
            )
        }
        val durationMs = WavDurationReader.durationMs(audioFile)
        if (durationMs != null && durationMs < MIN_UPLOAD_DURATION_MS) {
            return@withContext Result.failure(
                MiniMaxException(
                    TtsError(
                        TtsErrorCode.InvalidReferenceAudio,
                        "参考音频太短（云端要求约 10 秒以上），当前约 ${(durationMs / 1000)} 秒。",
                    ),
                ),
            )
        }
        val contentType = when (audioFile.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            else -> "audio/wav"
        }
        val request = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("purpose", "voice_clone")
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody(contentType.toMediaType()),
            )
            .build()
        val built = Request.Builder()
            .url(url(connection, "files/upload"))
            .header("Authorization", "Bearer ${connection.apiKey}")
            .post(request)
            .build()
        executeCall("files/upload", built, MAX_JSON_BYTES) { body, _ -> parseUploadFileId(body) }
    }

    /** Creates a cloned voice from an uploaded file_id (current JSON contract). */
    suspend fun cloneVoice(
        connection: MiniMaxConnection,
        fileId: Long,
        voiceId: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isValidCustomVoiceId(voiceId)) {
            return@withContext Result.failure(
                MiniMaxException(
                    TtsError(
                        TtsErrorCode.ApiServerError,
                        "云端音色标识格式不正确：需 8~256 位、英文字母开头、仅含字母/数字/中划线/下划线，且不能以中划线或下划线结尾。",
                    ),
                ),
            )
        }
        val body = JSONObject()
            .put("file_id", fileId)
            .put("voice_id", voiceId)
            .toString()
            .toRequestBody(jsonType)
        val built = Request.Builder()
            .url(url(connection, "voice_clone"))
            .header("Authorization", "Bearer ${connection.apiKey}")
            .post(body)
            .build()
        executeCall("voice_clone", built, MAX_JSON_BYTES) { responseBody, _ -> parseCloneResponse(responseBody) }
    }

    /**
     * Synthesizes text via t2a_v2 and writes a verified WAV to [outputFile]:
     * bytes land in a `.part` file first, get structure-checked (RIFF header +
     * parsable duration), then atomically replace the target. A failed or
     * cancelled run never leaves a half-written "success" file behind.
     */
    suspend fun synthesizeToFile(
        connection: MiniMaxConnection,
        voiceId: String,
        text: String,
        speed: Float,
        languageBoost: String? = null,
        outputFile: File,
        model: String = DEFAULT_MODEL,
    ): Result<File> = withContext(Dispatchers.IO) {
        val payload = buildSynthesisPayload(
            model = model,
            text = text,
            voiceId = voiceId,
            speed = speed,
            languageBoost = languageBoost,
        ).toString().toRequestBody(jsonType)
        val built = Request.Builder()
            .url(url(connection, "t2a_v2"))
            .header("Authorization", "Bearer ${connection.apiKey}")
            .post(payload)
            .build()
        val partFile = File(outputFile.parentFile, outputFile.name + ".part")
        try {
            val downloaded = executeCall("t2a_v2", built, MAX_SYNTHESIS_JSON_BYTES) { body, _ ->
                val audio = parseSynthesisAudio(body)
                when (audio) {
                    is SynthesisAudio.DownloadUrl -> downloadAudio(audio.url)
                    is SynthesisAudio.HexBytes -> audio.decodeStrict()
                }
            }.getOrElse { return@withContext Result.failure(it) }
            if (downloaded.isEmpty()) {
                return@withContext Result.failure(
                    MiniMaxException(TtsError(TtsErrorCode.ApiProtocolError, "云端返回了空音频。")),
                )
            }
            outputFile.parentFile?.mkdirs()
            partFile.writeBytes(downloaded)
            val durationMs = WavDurationReader.durationMs(partFile)
            if (durationMs == null || durationMs <= 0L) {
                return@withContext Result.failure(
                    MiniMaxException(
                        TtsError(
                            TtsErrorCode.ApiProtocolError,
                            "云端音频格式异常（无法解析 WAV 结构），已丢弃，不会保存为成功结果。",
                        ),
                    ),
                )
            }
            if (!partFile.renameTo(outputFile)) {
                partFile.copyTo(outputFile, overwrite = true)
                partFile.delete()
            }
            Result.success(outputFile)
        } finally {
            partFile.delete()
        }
    }

    /** Downloads an expiring audio URL without forwarding the Bearer header. */
    private fun downloadAudio(url: String): ByteArray {
        val parsed = url.toHttpUrl()
        val request = Request.Builder().url(parsed).get().build()
        client.newBuilder()
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) throw mapHttpError(response.code, response.request.url.host)
                val bytes = response.body?.bytes()
                    ?: throw ApiException(TtsErrorCode.ApiServerError, "云端音频下载失败（响应为空）。")
                if (bytes.size > MAX_DOWNLOAD_BYTES) {
                    throw ApiException(TtsErrorCode.ApiServerError, "云端音频文件过大。")
                }
                return bytes
            }
    }

    /**
     * Runs [build] against the API and maps every failure into a [MiniMaxException]
     * carrying a transport/HTTP/business/protocol-classified [TtsError]. The
     * OkHttp call is cancelled when the calling coroutine is cancelled, and
     * CancellationException always propagates untouched.
     */
    private suspend fun <T> executeCall(
        operation: String,
        request: Request,
        maxBodyBytes: Long,
        build: (String, MiniMaxDiagnostics) -> T,
    ): Result<T> {
        val startedAt = System.nanoTime()
        return try {
            val response = client.executeSuspending(request)
            response.use {
                // Reading the body can still hit transport failures (timeouts,
                // resets) — those must propagate to classifyTransport, so no
                // runCatching swallowing here.
                val bodyBytes = it.body?.bytes()
                when {
                    bodyBytes == null -> throw ApiException(
                        TtsErrorCode.ApiProtocolError,
                        "服务响应为空。",
                    )
                    bodyBytes.size > maxBodyBytes -> throw ApiException(
                        TtsErrorCode.ApiProtocolError,
                        "服务响应体异常过大（${bodyBytes.size / 1024 / 1024} MB），已放弃解析。",
                    )
                    !it.isSuccessful -> throw httpOrBusinessError(it.code, bodyBytes)
                }
                val bodyText = String(bodyBytes, Charsets.UTF_8)
                val diag = MiniMaxDiagnostics(
                    operation = operation,
                    host = request.url.host,
                    httpStatus = it.code,
                    elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L,
                )
                Result.success(build(bodyText, diag))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            val diag = MiniMaxDiagnostics(
                operation = operation,
                host = request.url.host,
                elapsedMs = elapsedMs,
            )
            val ttsError = classifyTransport(error, operation)
            val withDiag = if (diag.host.isNotBlank() && ttsError.causeMessage.isNullOrBlank()) {
                ttsError.copy(causeMessage = diag.toString())
            } else {
                ttsError
            }
            Result.failure(MiniMaxException(withDiag))
        }
    }

    private fun httpOrBusinessError(httpCode: Int, bodyBytes: ByteArray): Throwable {
        val bodyText = String(bodyBytes, Charsets.UTF_8)
        // Try the business error first: MiniMax returns a JSON base_resp even
        // for many non-2xx statuses; fall back to HTTP classification.
        val business = runCatching { mapApiError(JSONObject(bodyText), "HTTP $httpCode") }.getOrNull()
        if (business is ApiException) {
            val code = when (httpCode) {
                401, 403 -> TtsErrorCode.ApiUnauthorized
                429 -> TtsErrorCode.ApiRateLimited
                else -> business.code
            }
            return ApiException(code, business.userMessage)
        }
        return mapHttpError(httpCode, null)
    }

    private fun mapHttpError(code: Int, host: String?): Throwable = when (code) {
        401, 403 -> ApiException(TtsErrorCode.ApiUnauthorized, "API Key 无效、区域不匹配或无权限（HTTP $code）")
        402 -> ApiException(TtsErrorCode.ApiInsufficientBalance, "账户余额不足或未开通（HTTP 402）")
        404 -> ApiException(
            TtsErrorCode.ApiProtocolError,
            "接口路径不存在（HTTP 404），服务区域或域名可能不匹配。",
        )
        429 -> ApiException(TtsErrorCode.ApiRateLimited, "请求过于频繁（HTTP 429）")
        in 500..599 -> ApiException(TtsErrorCode.ApiServerError, "云端服务异常（HTTP $code）")
        else -> ApiException(TtsErrorCode.ApiServerError, "云端返回异常状态（HTTP $code）")
    }

    private fun classifyTransport(error: Throwable, operation: String): TtsError = when (error) {
        is ApiException -> TtsError(error.code, error.userMessage)
        is MiniMaxException -> error.error
        is SSLException -> TtsError(
            TtsErrorCode.NetworkUnavailable,
            "安全连接失败，请检查设备时间或网络代理。",
            "$operation ssl",
        )
        is UnknownHostException -> TtsError(
            TtsErrorCode.NetworkUnavailable,
            "无法连接服务：域名解析失败，请检查网络与服务区域。",
            "$operation dns",
        )
        is SocketTimeoutException -> TtsError(
            TtsErrorCode.GenerationTimeout,
            "服务响应超时，请稍后重试。",
            "$operation timeout",
        )
        is IOException -> TtsError(
            TtsErrorCode.NetworkUnavailable,
            "网络不可用，请检查网络连接。",
            "$operation io:${error.javaClass.simpleName}",
        )
        is JSONException -> TtsError(
            TtsErrorCode.ApiProtocolError,
            "服务响应格式异常（非 JSON），可能是网络被网关拦截或服务区域不符。",
            "$operation parse",
        )
        else -> TtsError(TtsErrorCode.Unknown, "云端请求失败（${error.javaClass.simpleName}）。", operation)
    }

    private fun url(connection: MiniMaxConnection, path: String): String {
        // Tolerate stored base URLs that already carry the /v1 suffix; build the
        // final URL through HttpUrl so parameters are canonically encoded.
        val base = connection.baseUrl.trim().trimEnd('/').removeSuffix("/v1")
        val endpoint = if (path.startsWith("v1/")) path else "v1/$path"
        val httpUrl = "$base/$endpoint".toHttpUrl()
        return if (connection.groupId.isNullOrBlank()) {
            httpUrl
        } else {
            httpUrl.newBuilder().setQueryParameter("GroupId", connection.groupId).build()
        }.toString()
    }

    /** okhttp enqueue bridged into a cancellable coroutine; call.cancel() on scope exit. */
    private suspend fun OkHttpClient.executeSuspending(request: Request): Response {
        val call = newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            })
        }
    }

    /** data.audio payload: an expiring download URL or hex-encoded bytes. */
    internal sealed interface SynthesisAudio {
        data class DownloadUrl(val url: String) : SynthesisAudio
        data class HexBytes(val hex: String) : SynthesisAudio
    }

    companion object {
        /** Current official recommendation for voice cloning quality output. */
        const val DEFAULT_MODEL = "speech-2.8-hd"

        /** Clone audio limits from the official guide: mp3/m4a/wav, 10 s ~ 5 min, <= 20 MB. */
        const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024
        const val MIN_UPLOAD_DURATION_MS = 9_000L
        const val VOICE_TYPE_SYSTEM = "system"
        const val VOICE_TYPE_CLONED = "voice_cloning"

        private const val MAX_JSON_BYTES = 8L * 1024 * 1024
        private const val MAX_SYNTHESIS_JSON_BYTES = 96L * 1024 * 1024
        private const val MAX_DOWNLOAD_BYTES = 128L * 1024 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * Pure request builder used by the provider and contract tests.
         * output_format=hex keeps the audio inline (no second network hop).
         */
        internal fun buildSynthesisPayload(
            model: String,
            text: String,
            voiceId: String,
            speed: Float,
            languageBoost: String?,
        ): JSONObject = JSONObject()
            .put("model", model)
            .put("text", text)
            .put("stream", false)
            .put("output_format", "hex")
            .apply { putOpt("language_boost", languageBoost?.takeIf { it.isNotBlank() }) }
            .put(
                "voice_setting",
                JSONObject()
                    .put("voice_id", voiceId)
                    .put("speed", speed.coerceIn(0.5f, 2.0f).toDouble())
                    .put("vol", 1.0)
                    .put("pitch", 0),
            )
            .put(
                "audio_setting",
                JSONObject()
                    .put("sample_rate", 24000)
                    .put("bitrate", 128000)
                    .put("format", "wav")
                    .put("channel", 1),
            )

        /**
         * Official voice_id rules: length [8, 256], starts with an English
         * letter, letters/digits/-/_ only, must not end with - or _.
         */
        fun isValidCustomVoiceId(voiceId: String): Boolean {
            if (voiceId.length !in 8..256) return false
            if (!voiceId.first().isLetter()) return false
            if (!voiceId.last().isLetterOrDigit()) return false
            return voiceId.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        }

        /** Maps a base_resp block into a business TtsError with a Chinese message. */
        internal fun mapApiError(json: JSONObject, fallback: String): Throwable {
            val resp = json.optJSONObject("base_resp")
            val statusCode = resp?.optInt("status_code", -1) ?: -1
            val statusMsg = resp?.optString("status_msg").takeUnless { it.isNullOrBlank() }
                ?: json.optString("message").takeUnless { it.isNullOrBlank() }
                ?: fallback
            val code = when (statusCode) {
                1004, 2038 -> TtsErrorCode.ApiUnauthorized
                1008 -> TtsErrorCode.ApiInsufficientBalance
                1002, 1039 -> TtsErrorCode.ApiRateLimited
                1001 -> TtsErrorCode.GenerationTimeout
                1043 -> TtsErrorCode.InvalidReferenceAudio
                2054, 2013 -> TtsErrorCode.ApiServerError
                else -> TtsErrorCode.ApiServerError
            }
            val userMessage = when (statusCode) {
                1004 -> "API Key 无效或无权限，请检查 Key 与所选服务区域是否匹配。"
                2038 -> "当前账号没有音色克隆权限，请先在云端控制台完成认证。"
                1008 -> "账号余额不足，请到云端控制台充值后再试。"
                1002, 1039 -> "云端请求过于频繁，请稍后重试。"
                1001 -> "云端处理超时，请稍后重试。"
                1043 -> "参考音频与验证文本不一致，克隆被拒绝。"
                2054 -> "云端音色不存在或已过期，请重新克隆。"
                2013 -> "云端参数不合法：${sanitize(statusMsg)}"
                else -> sanitize(statusMsg)
            }
            return ApiException(code, userMessage)
        }

        /** Status/server strings are truncated before they reach any UI surface. */
        private fun sanitize(message: String): String =
            message.replace(Regex("\\s+"), " ").trim().take(120)

        /**
         * A successful catalog response MUST carry base_resp.status_code == 0.
         * `{}` / HTML / truncated bodies are protocol errors, never ONLINE.
         */
        internal fun requireOk(jsonText: String): JSONObject {
            if (jsonText.isBlank()) {
                throw JSONException("empty body")
            }
            val trimmed = jsonText.trimStart()
            if (trimmed.startsWith("<")) {
                throw JSONException("html body")
            }
            val json = JSONObject(jsonText)
            val resp = json.optJSONObject("base_resp")
                ?: throw JSONException("missing base_resp")
            val statusCode = resp.optInt("status_code", -1)
            if (statusCode != 0) {
                throw mapApiError(json, "云端返回业务错误")
            }
            return json
        }

        /**
         * Parses a get_voice response for the requested category. An empty list
         * with base_resp==0 is a legal "no voices yet" state; a missing
         * base_resp is a protocol error (see [requireOk]).
         */
        internal fun parseVoiceCatalog(jsonText: String, voiceType: String): MiniMaxVoiceCatalog {
            val json = requireOk(jsonText)
            val system = parseVoiceArray(json.optJSONArray("system_voice"), isCloned = false)
            val cloned = parseVoiceArray(json.optJSONArray("voice_cloning"), isCloned = true)
            return MiniMaxVoiceCatalog(systemVoices = system, clonedVoices = cloned)
        }

        private fun parseVoiceArray(list: org.json.JSONArray?, isCloned: Boolean): List<MiniMaxCloudVoice> {
            if (list == null) return emptyList()
            return buildList {
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    val voiceId = item.optString("voice_id")
                    if (voiceId.isBlank()) continue
                    val name = item.optString("voice_name").ifBlank {
                        item.optJSONArray("description")?.optString(0).orEmpty()
                    }.ifBlank { voiceId }
                    add(MiniMaxCloudVoice(voiceId = voiceId, displayName = name, isCloned = isCloned))
                }
            }
        }

        /** Back-compat parser used by older unit tests: cloned list only. */
        internal fun parseClonedVoices(jsonText: String): List<MiniMaxCloudVoice> =
            parseVoiceCatalog(jsonText, VOICE_TYPE_CLONED).clonedVoices

        /** Pure JSON contract parser for POST /v1/files/upload -> file.file_id. */
        internal fun parseUploadFileId(jsonText: String): Long {
            val json = requireOk(jsonText)
            val fileId = json.optJSONObject("file")?.optLong("file_id", -1L) ?: -1L
            if (fileId <= 0L) {
                throw mapApiError(json, "云端上传未返回 file_id")
            }
            return fileId
        }

        /**
         * Pure JSON contract parser for POST /v1/voice_clone. The official
         * response echoes no voice_id; success is base_resp.status_code == 0.
         */
        internal fun parseCloneResponse(jsonText: String): String {
            val json = requireOk(jsonText)
            return json.optString("voice_id")
                .ifBlank { json.optJSONObject("data")?.optString("voice_id").orEmpty() }
        }

        /**
         * Pure JSON contract parser for POST /v1/t2a_v2: data.audio is an
         * expiring URL when output_format=url, or hex-encoded audio otherwise.
         */
        internal fun parseSynthesisAudio(jsonText: String): SynthesisAudio {
            val json = requireOk(jsonText)
            val audio = json.optJSONObject("data")?.optString("audio")
                .takeUnless { it.isNullOrBlank() }
                ?: throw mapApiError(json, "云端合成未返回音频")
            return if (audio.startsWith("http://") || audio.startsWith("https://")) {
                SynthesisAudio.DownloadUrl(audio)
            } else {
                SynthesisAudio.HexBytes(audio)
            }
        }

        /**
         * Strict hex decode: every character must be a valid hex digit. The old
         * decoder silently turned Character.digit()==-1 into 0xff nibbles.
         */
        internal fun hexToBytes(hex: String): ByteArray {
            val clean = hex.replace("\n", "").replace("\r", "").replace(" ", "")
            require(clean.length % 2 == 0) { "hex 音频长度非法" }
            val out = ByteArray(clean.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(clean[i * 2], 16)
                val lo = Character.digit(clean[i * 2 + 1], 16)
                require(hi in 0..15 && lo in 0..15) { "hex 音频包含非法字符" }
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }

        private fun SynthesisAudio.HexBytes.decodeStrict(): ByteArray = hexToBytes(hex)
    }
}

class MiniMaxException(val error: TtsError) : Exception(error.userMessage)

internal class ApiException(
    val code: TtsErrorCode,
    val userMessage: String,
) : Exception(userMessage) {
    fun userMessageFor(fallback: String): String = userMessage.takeIf { it.isNotBlank() } ?: fallback
}
