package com.shinevoice.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Public, unauthenticated GitHub Releases client. No token is bundled. */
class GitHubReleaseClient(
    private val endpoint: String = UpdateProtocol.RELEASES_LATEST_URL,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun fetchLatest(): Result<UpdateCandidate> = withContext(Dispatchers.IO) {
        runCatching {
            val releaseJson = getText(endpoint)
            val release = UpdateProtocol.parseRelease(releaseJson)
            val metadataAsset = release.assets.firstOrNull { it.name == "update.json" }
                ?: throw UpdateProtocolException("Release 缺少 update.json")
            val metadataJson = getText(metadataAsset.downloadUrl)
            UpdateProtocol.parseCandidate(releaseJson, metadataJson)
        }.mapCatching { candidate ->
            UpdateProtocol.requireGitHubAssetUrl(candidate.apkUrl)
            candidate
        }.mapError { throwable ->
            when (throwable) {
                is UpdateProtocolException -> throwable
                is IOException -> UpdateNetworkException("无法连接更新服务器，请稍后重试。", throwable)
                else -> UpdateProtocolException("更新信息读取失败，请稍后重试。")
            }
        }
    }

    private fun getText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ShineVoice-Updater")
            .get()
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val message = when (response.code) {
                    403 -> "GitHub 请求受限，请稍后重试。"
                    404 -> "暂未找到可用的正式更新。"
                    in 500..599 -> "GitHub 服务暂时异常，请稍后重试。"
                    else -> "更新服务器返回异常（HTTP ${response.code}）。"
                }
                throw UpdateNetworkException(message)
            }
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw UpdateNetworkException("更新服务器返回为空。")
        }
    }
}

class UpdateNetworkException(message: String, cause: Throwable? = null) : IOException(message, cause)

private inline fun <T> Result<T>.mapError(transform: (Throwable) -> Throwable): Result<T> =
    exceptionOrNull()?.let { Result.failure(transform(it)) } ?: this
