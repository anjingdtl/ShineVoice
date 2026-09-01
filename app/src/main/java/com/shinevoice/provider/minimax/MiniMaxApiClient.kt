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
 * Minimal MiniMax T2A / voice-clone API client (BYOK).
 *
 * Endpoints follow the public MiniMax contract:
 *   GET  /v1/voice/list?GroupId={groupId}          -> list cloned voices
 *   POST /v1/voice_clone?GroupId={groupId}         -> clone from reference audio
 *   POST /v1/t2a_v2?GroupId={groupId}              -> synthesize to base64 audio
 *
 * Network/API contract details cannot be executed in the sandbox CI; the client
 * is isolated so contract adjustments do not leak into the provider or UI.
 * The Authorization header is never logged.
 */
class MiniMaxApiClient(
    private val baseUrl: String = "https://api.minimax.chat/v1/",
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** Lightweight authenticated probe: lists cloned voices for the account. */
    suspend fun testConnection(apiKey: String, groupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val response = client.newCall(
                Request.Builder()
                    .url(baseUrl + "voice/list?GroupId=$groupId")
                    .header("Authorization", "Bearer $apiKey")
                    .get()
                    .build(),
            ).execute()
            response.use {
                if (!it.isSuccessful) throw mapHttpError(it.code)
                Unit
            }
        }.mapError("连接测试失败")
    }

    /** Lists previously cloned voices: voice_id -> name. */
    suspend fun listVoices(apiKey: String, groupId: String): Result<List<ClonedVoice>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = client.newCall(
                    Request.Builder()
                        .url(baseUrl + "voice/list?GroupId=$groupId")
                        .header("Authorization", "Bearer $apiKey")
                        .get()
                        .build(),
                ).execute()
                response.use {
                    if (!it.isSuccessful) throw mapHttpError(it.code)
                    parseVoiceList(it.body?.string() ?: "{}")
                }
            }.mapError("无法读取云端音色列表")
        }

    /** Uploads the reference audio and returns the new remote voice_id. */
    suspend fun cloneVoice(
        apiKey: String,
        groupId: String,
        audioFile: File,
        voiceId: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        val request = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "voice_clone")
            .addFormDataPart("voice_id", voiceId)
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody("audio/wav".toMediaType()),
            )
            .build()
        runCatching {
            val response = client.newCall(
                Request.Builder()
                    .url(baseUrl + "voice_clone?GroupId=$groupId")
                    .header("Authorization", "Bearer $apiKey")
                    .post(request)
                    .build(),
            ).execute()
            response.use {
                if (!it.isSuccessful) throw mapHttpError(it.code)
                parseCloneVoiceId(it.body?.string() ?: "{}")
            }
        }.mapError("云端音色克隆失败")
    }

    /**
     * Synthesizes text to a WAV file. The API returns base64 audio in
     * data.audio; decoded bytes are written to [outputFile].
     */
    suspend fun synthesizeToFile(
        apiKey: String,
        groupId: String,
        voiceId: String,
        text: String,
        speed: Float,
        outputFile: File,
    ): Result<File> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", "speech-02-hd")
            .put("text", text)
            .put("stream", false)
            .put(
                "voice_setting",
                JSONObject()
                    .put("voice_id", voiceId)
                    .put("speed", speed.coerceIn(0.5f, 2.0f))
                    .put("audio_format", "wav"),
            )
            .toString()
            .toRequestBody(jsonType)
        runCatching {
            val response = client.newCall(
                Request.Builder()
                    .url(baseUrl + "t2a_v2?GroupId=$groupId")
                    .header("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build(),
            ).execute()
            response.use {
                if (!it.isSuccessful) throw mapHttpError(it.code)
                val json = JSONObject(it.body?.string() ?: "{}")
                val audioBase64 = parseSynthesisAudio(json)
                outputFile.parentFile?.mkdirs()
                val bytes = java.util.Base64.getDecoder().decode(audioBase64)
                outputFile.writeBytes(bytes)
                outputFile
            }
        }.mapError("云端生成失败")
    }

    private fun mapHttpError(code: Int): Throwable = when (code) {
        401, 403 -> ApiException(TtsErrorCode.ApiUnauthorized, "API Key 无效或无权限（HTTP $code）")
        429 -> ApiException(TtsErrorCode.ApiRateLimited, "请求过于频繁（HTTP 429）")
        in 500..599 -> ApiException(TtsErrorCode.ApiServerError, "云端服务异常（HTTP $code）")
        402 -> ApiException(TtsErrorCode.ApiUnauthorized, "账户余额不足或未开通（HTTP 402）")
        else -> ApiException(TtsErrorCode.ApiServerError, "云端返回异常状态（HTTP $code）")
    }

    private fun <T> Result<T>.mapError(fallback: String): Result<T> {
        val error = exceptionOrNull() ?: return this
        val ttsError = when (error) {
            is ApiException -> TtsError(error.code, error.userMessageFor(fallback), error.message)
            is SocketTimeoutException -> TtsError(TtsErrorCode.GenerationTimeout, "云端请求超时，请稍后重试。", error.message)
            is java.io.IOException -> TtsError(TtsErrorCode.NetworkUnavailable, "网络不可用，请检查网络连接。", error.message)
            else -> TtsError(TtsErrorCode.Unknown, fallback, error.message)
        }
        return Result.failure(MiniMaxException(ttsError))
    }

    data class ClonedVoice(val voiceId: String, val name: String)

    companion object {
        /** Maps a MiniMax base_resp JSON into a business TtsError. */
        internal fun mapApiError(json: JSONObject, fallback: String): Throwable {
            val resp = json.optJSONObject("base_resp")
            val message = resp?.optString("status_msg") ?: json.optString("message")
            return ApiException(TtsErrorCode.ApiServerError, message.ifBlank { fallback })
        }

        /** Pure JSON contract parser for GET /v1/voice/list (unit-testable). */
        internal fun parseVoiceList(jsonText: String): List<ClonedVoice> {
            val json = JSONObject(jsonText)
            val data = json.optJSONObject("data")
            val list = data?.optJSONArray("voice_list") ?: json.optJSONArray("voice_list")
            return buildList {
                if (list != null) {
                    for (i in 0 until list.length()) {
                        val item = list.getJSONObject(i)
                        val voiceId = item.optString("voice_id").ifBlank { continue }
                        add(ClonedVoice(voiceId, item.optString("voice_name").ifBlank { voiceId }))
                    }
                }
            }
        }

        /** Pure JSON contract parser for POST /v1/voice_clone. */
        internal fun parseCloneVoiceId(jsonText: String): String {
            val json = JSONObject(jsonText)
            return json.optString("voice_id")
                .ifBlank { json.optJSONObject("data")?.optString("voice_id") }
                .ifBlank {
                    throw mapApiError(json, "云端克隆未返回 voice_id")
                }
        }

        /** Pure JSON contract parser for POST /v1/t2a_v2; returns base64 audio. */
        internal fun parseSynthesisAudio(jsonText: String): String {
            val json = JSONObject(jsonText)
            if (json.optJSONObject("base_resp")?.optInt("status_code", 0) != 0) {
                throw mapApiError(json, "云端合成失败")
            }
            return json.optJSONObject("data")?.optString("audio")
                .takeUnless { it.isNullOrBlank() }
                ?: throw mapApiError(json, "云端合成未返回音频")
        }
    }
}

class MiniMaxException(val error: TtsError) : Exception(error.userMessage)

private class ApiException(
    val code: TtsErrorCode,
    val userMessage: String,
) : Exception(userMessage) {
    fun userMessageFor(fallback: String): String = userMessage.takeIf { it.isNotBlank() } ?: fallback
}