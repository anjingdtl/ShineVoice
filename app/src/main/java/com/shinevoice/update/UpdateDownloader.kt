package com.shinevoice.update

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Downloads only into the private cache/updates directory and never exposes a .part file. */
class UpdateDownloader(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun download(
        candidate: UpdateCandidate,
        cacheDir: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(UpdateProtocol.isSafeApkName(candidate.metadata.apkName)) { "安装包文件名不安全" }
            val updatesDir = File(cacheDir, UpdateProtocol.UPDATE_CACHE_DIR).canonicalFile
            require(updatesDir.parentFile == cacheDir.canonicalFile) { "更新目录非法" }
            updatesDir.mkdirs()
            require(updatesDir.isDirectory) { "无法创建更新缓存目录" }
            val target = File(updatesDir, candidate.metadata.apkName).canonicalFile
            require(target.parentFile == updatesDir) { "安装包路径越界" }
            val part = File(updatesDir, "${candidate.metadata.apkName}.part")
            part.delete()
            require(!target.exists() || target.delete()) { "无法清理旧安装包" }

            val request = Request.Builder()
                .url(UpdateProtocol.requireGitHubAssetUrl(candidate.apkUrl))
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "ShineVoice-Updater")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw UpdateNetworkException("安装包下载失败（HTTP ${response.code}）。")
                val body = response.body ?: throw UpdateNetworkException("安装包下载响应为空。")
                val total = candidate.metadata.apkSizeBytes
                var downloaded = 0L
                FileOutputStream(part).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        onProgress(downloaded, total)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            downloaded += count
                            onProgress(downloaded, total)
                        }
                    }
                    output.fd.sync()
                }
                if (downloaded != total) {
                    throw UpdateNetworkException("安装包大小校验失败。")
                }
            }
            require(part.renameTo(target)) { "无法完成安装包落盘" }
            require(target.isFile && target.length() == candidate.metadata.apkSizeBytes) { "安装包未完整落盘" }
            target
        }.onFailure {
            val updatesDir = runCatching { File(cacheDir, UpdateProtocol.UPDATE_CACHE_DIR).canonicalFile }.getOrNull()
            updatesDir?.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        }
    }
}
