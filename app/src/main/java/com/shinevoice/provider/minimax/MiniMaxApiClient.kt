package com.shinevoice.provider.minimax

import com.shinevoice.domain.tts.TtsError
import com.shinevoice.domain.tts.TtsErrorCode
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * MiniMax voice-clone / T2A client following the current official contract:
 *
 *   POST {base}/v1/files/upload        multipart purpose=voice_clone -> file.file_id
 *   POST {base}/v1/voice_clone         JSON  {file_id, voice_id}     -> base_resp
 *   POST {base}/v1/get_voice           JSON  {voice_type}            -> cloned voice list
 *   POST {base}/v1/t2a_v2              JSON  voice_setting/audio_setting -> data.audio
 *
 * Region base URLs are configurable (api.minimax.cn / api.minimax.io) so an
 * outdated domain is never hard-wired; the legacy GroupId query parameter is
 * appended only when the account provides one. t2a_v2 responds with
 * output_format=url (download link) or hex-encoded audio in data.audio — it is
 * never assumed to be base64. The Authorization header is never logged.
 */
class MiniMaxApiClient(
    private val connectTimeoutMs: Long = 20_000L,
    private val readTimeoutMs: Long = 60_000L,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** Lists cloned voices via the Voice Management API (no quota consumed). */
    suspend fun listVoices(
        apiKey: String,
        baseUrl: String,
        groupId: String?,
    ): Result<List<ClonedVoice>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = client.newCall(
                Request.Builder()
                    .url(url(baseUrl, "get_voice", groupId))
                    .header("Authorization", "Bearer $apiKey")
                    .post(JSONObject().put("voice_type", "voice_cloning").toString().toRequestBody(jsonType))
                    .build(),
            ).execute()
            response.use {
                if (!it.isSuccessful) throw mapHttpError(it.code)
                parseClonedVoices(it.body?.string() ?: "{}")
            }
        }.mapError("无法读取云端音色列表")
    }

    /** Uploads reference audio for cloning; returns the official file_id. */
    suspend fun uploadReferenceAudio(
        apiKey: String,
        baseUrl: String,
        groupId: String?,
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
        runCatching {
            val response = client.newCall(
                Request.Builder()
                    .url(url(baseUrl, "files/upload", groupId))
                    .header("Authorization", "Bearer $apiKey")
                    .post(request)
                    .build(),
            ).execute()
            response.use {
                if (!it.isSuccessful) throw mapHttpError(it.code)
                parseUploadFileId(it.body?.string() ?: "{}")
            }
        }.mapError("参考音频上传失败")
    }

    /** Creates a cloned voice from an uploaded file_id (current JSON contract). */
    suspend fun cloneVoice(
        apiKey: String,
        baseUrl: String,
        groupId: String?,
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
        runCatching {
            val response = client.newCall(
                Request.Builder()
                    .url(url(baseUrl, "voice_clone", groupId))
                    .header("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build(),
            ).execute()
            response.use {
                if (!it.isSuccessful) throw mapHttpError(it.code)
                parseCloneResponse(it.body?.string() ?: "{}")
            }
        }.mapError("云端音色克隆失败")
    }

    /**
     * Synthesizes text via t2a_v2 and writes a WAV to [outputFile].
     * Uses output_format=url (official default is hex; we request the download
     * link and fall back to hex decoding when the server returns hex bytes).
     */
    suspend fun synthesizeToFile(
        apiKey: String,
        baseUrl: String,
        groupId: String?,
        voiceId: String,
        text: String,
        speed: Float,
        outputFile: File,
        model: String = DEFAULT_MODEL,
    ): Result<File> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("text", text)
            .put("stream", false)
            .put("output_format", "url")
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
            .toString()
            .toRequestBody(jsonType)
        runCatching {
            val response = client.newCall(
                Request.Builder()
                    .url(url(baseUrl, "t2a_v2", groupId))
                    .header("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build(),
            ).execute()
            response.use {
                if (!it.isSuccessful) throw mapHttpError(it.code)
                val audio = parseSynthesisAudio(it.body?.string() ?: "{}")
                outputFile.parentFile?.mkdirs()
                when (audio) {
                    is SynthesisAudio.DownloadUrl -> downloadTo(audio.url, outputFile)
                    is SynthesisAudio.HexBytes -> outputFile.writeBytes(audio.decode())
                }
                outputFile
            }
        }.mapError("云端生成失败")
    }

    private fun downloadTo(url: String, outputFile: File) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw mapHttpError(response.code)
            val bytes = response.body?.bytes()
                ?: throw ApiException(TtsErrorCode.ApiServerError, "云端音频下载失败（响应为空）。")
            outputFile.writeBytes(bytes)
        }
    }

    private fun url(baseUrl: String, path: String, groupId: String?): String {
        val base = baseUrl.trimEnd('/')
        val endpoint = if (path.startsWith("v1/")) path else "v1/$path"
        return if (groupId.isNullOrBlank()) {
            "$base/$endpoint"
        } else {
            "$base/$endpoint?GroupId=$groupId"
        }
    }

    private fun mapHttpError(code: Int): Throwable = when (code) {
        401, 403 -> ApiException(TtsErrorCode.ApiUnauthorized, "API Key 无效或无权限（HTTP $code）")
        402 -> ApiException(TtsErrorCode.ApiUnauthorized, "账户余额不足或未开通（HTTP 402）")
        429 -> ApiException(TtsErrorCode.ApiRateLimited, "请求过于频繁（HTTP 429）")
        in 500..599 -> ApiException(TtsErrorCode.ApiServerError, "云端服务异常（HTTP $code）")
        else -> ApiException(TtsErrorCode.ApiServerError, "云端返回异常状态（HTTP $code）")
    }

    private fun <T> Result<T>.mapError(fallback: String): Result<T> {
        val error = exceptionOrNull() ?: return this
        val ttsError = when (error) {
            is ApiException -> TtsError(error.code, error.userMessageFor(fallback), error.message)
            is MiniMaxException -> error.error
            is SocketTimeoutException -> TtsError(TtsErrorCode.GenerationTimeout, "云端请求超时，请稍后重试。", error.message)
            is java.io.IOException -> TtsError(TtsErrorCode.NetworkUnavailable, "网络不可用，请检查网络连接。", error.message)
            else -> TtsError(TtsErrorCode.Unknown, fallback, error.message)
        }
        return Result.failure(MiniMaxException(ttsError))
    }

    data class ClonedVoice(val voiceId: String, val name: String)

    /** data.audio payload: an expiring download URL or hex-encoded bytes. */
    internal sealed interface SynthesisAudio {
        data class DownloadUrl(val url: String) : SynthesisAudio
        data class HexBytes(val hex: String) : SynthesisAudio

        fun decode(): ByteArray = if (this is HexBytes) {
            hexToBytes(hex)
        } else {
            throw IllegalStateException("URL payload must be downloaded, not decoded")
        }
    }

    companion object {
        /** Current official recommendation for voice cloning quality output. */
        const val DEFAULT_MODEL = "speech-2.8-hd"

        /** Clone audio limits from the official guide: mp3/m4a/wav, 10 s ~ 5 min, <= 20 MB. */
        const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024

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
                1002, 1039 -> TtsErrorCode.ApiRateLimited
                1001 -> TtsErrorCode.GenerationTimeout
                1043 -> TtsErrorCode.InvalidReferenceAudio
                2013 -> TtsErrorCode.ApiServerError
                else -> TtsErrorCode.ApiServerError
            }
            val userMessage = when (statusCode) {
                1004 -> "API Key 无效，请检查后重试。"
                2038 -> "当前账号没有音色克隆权限，请先在云端控制台完成认证。"
                1002, 1039 -> "云端请求过于频繁，请稍后重试。"
                1001 -> "云端处理超时，请稍后重试。"
                1043 -> "参考音频与验证文本不一致，克隆被拒绝。"
                2013 -> "云端参数不合法：$statusMsg"
                else -> statusMsg
            }
            return ApiException(code, userMessage)
        }

        /** Requires base_resp.status_code == 0; returns the raw response object. */
        private fun requireOk(json: JSONObject): JSONObject {
            val statusCode = json.optJSONObject("base_resp")?.optInt("status_code", -1) ?: 0
            if (json.has("base_resp") && statusCode != 0) {
                throw mapApiError(json, "云端返回业务错误")
            }
            return json
        }

        /** Pure JSON contract parser for POST /v1/get_voice (voice_cloning list). */
        internal fun parseClonedVoices(jsonText: String): List<ClonedVoice> {
            val json = requireOk(JSONObject(jsonText))
            val list = json.optJSONArray("voice_cloning") ?: return emptyList()
            return buildList {
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    val voiceId = item.optString("voice_id")
                    if (voiceId.isBlank()) continue
                    val name = item.optString("voice_name").ifBlank {
                        item.optJSONArray("description")?.optString(0).orEmpty()
                    }.ifBlank { voiceId }
                    add(ClonedVoice(voiceId, name))
                }
            }
        }

        /** Pure JSON contract parser for POST /v1/files/upload -> file.file_id. */
        internal fun parseUploadFileId(jsonText: String): Long {
            val json = requireOk(JSONObject(jsonText))
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
            val json = requireOk(JSONObject(jsonText))
            // Older contracts echoed data.voice_id / voice_id; prefer the echo
            // when present, otherwise the caller's requested id is authoritative.
            return json.optString("voice_id")
                .ifBlank { json.optJSONObject("data")?.optString("voice_id").orEmpty() }
        }

        /**
         * Pure JSON contract parser for POST /v1/t2a_v2: data.audio is an
         * expiring URL when output_format=url, or hex-encoded audio otherwise.
         */
        internal fun parseSynthesisAudio(jsonText: String): SynthesisAudio {
            val json = requireOk(JSONObject(jsonText))
            val audio = json.optJSONObject("data")?.optString("audio")
                .takeUnless { it.isNullOrBlank() }
                ?: throw mapApiError(json, "云端合成未返回音频")
            return if (audio.startsWith("http://") || audio.startsWith("https://")) {
                SynthesisAudio.DownloadUrl(audio)
            } else {
                SynthesisAudio.HexBytes(audio)
            }
        }

        internal fun hexToBytes(hex: String): ByteArray {
            val clean = hex.replace("\n", "").replace("\r", "").replace(" ", "")
            require(clean.length % 2 == 0) { "hex 音频长度非法" }
            return ByteArray(clean.length / 2) { i ->
                ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
            }
        }
    }
}

class MiniMaxException(val error: TtsError) : Exception(error.userMessage)

internal class ApiException(
    val code: TtsErrorCode,
    val userMessage: String,
) : Exception(userMessage) {
    fun userMessageFor(fallback: String): String = userMessage.takeIf { it.isNotBlank() } ?: fallback
}
