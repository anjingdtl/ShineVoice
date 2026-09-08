package com.shinevoice.update

import java.net.URI
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class UpdateMetadata(
    val versionName: String,
    val versionCode: Long,
    val apkName: String,
    val apkUrl: String,
    val sha256: String,
    val forceUpdate: Boolean,
    val minimumVersionCode: Long,
    val title: String,
    val notes: List<String>,
    val apkSizeBytes: Long,
)

data class GitHubAsset(
    val name: String,
    val downloadUrl: String,
)

data class GitHubRelease(
    val tagName: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<GitHubAsset>,
)

data class UpdateCandidate(
    val tagName: String,
    val metadata: UpdateMetadata,
    val apkAsset: GitHubAsset,
    val metadataAsset: GitHubAsset,
) {
    val apkUrl: String get() = metadata.apkUrl.ifBlank { apkAsset.downloadUrl }
}

enum class UpdateDecision {
    Latest,
    UpdateAvailable,
}

class UpdateProtocolException(message: String) : IllegalArgumentException(message)

/** Strict parser and validator for the public GitHub Releases contract. */
object UpdateProtocol {
    const val OWNER = "anjingdtl"
    const val REPOSITORY = "ShineVoice"
    const val RELEASES_LATEST_URL = "https://api.github.com/repos/$OWNER/$REPOSITORY/releases/latest"
    const val EXPECTED_PACKAGE_NAME = "com.shinevoice"
    const val EXPECTED_SIGNER_SHA256 = "017b3fbed4001083f2f70a0c51e8e463322df66b095e1c3a476fdd0d86dc2a0a"
    const val UPDATE_CACHE_DIR = "updates"

    private val tagPattern = Regex("^V(\\d+\\.\\d+\\.\\d+)$")
    private val shaPattern = Regex("^[a-fA-F0-9]{64}$")
    private val apkNamePattern = Regex("^ShineVoice-V\\d+\\.\\d+\\.\\d+-release\\.apk$")

    fun expectedApkName(versionName: String): String {
        val normalized = normalizeVersionName(versionName)
        return "ShineVoice-V$normalized-release.apk"
    }

    fun normalizeVersionName(versionName: String): String {
        val normalized = versionName.trim().removePrefix("V").removePrefix("v")
        if (!Regex("^\\d+\\.\\d+\\.\\d+$").matches(normalized)) {
            throw UpdateProtocolException("版本号必须是 x.y.z：$versionName")
        }
        return normalized
    }

    fun parseRelease(jsonText: String): GitHubRelease {
        val json = runCatching { JSONObject(jsonText) }
            .getOrElse { throw UpdateProtocolException("GitHub Release JSON 无效") }
        val tagName = json.optString("tag_name").trim()
        if (!tagPattern.matches(tagName)) throw UpdateProtocolException("Release tag 无效：$tagName")
        if (!json.has("draft") || json.opt("draft") !is Boolean) {
            throw UpdateProtocolException("Release draft 字段无效")
        }
        if (!json.has("prerelease") || json.opt("prerelease") !is Boolean) {
            throw UpdateProtocolException("Release prerelease 字段无效")
        }
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
            throw UpdateProtocolException("仅接受已发布的 stable Release")
        }
        val assetsJson = json.optJSONArray("assets") ?: throw UpdateProtocolException("Release 缺少 assets")
        val assets = buildList {
            for (index in 0 until assetsJson.length()) {
                val asset = assetsJson.optJSONObject(index) ?: continue
                val name = asset.optString("name").trim()
                val url = asset.optString("browser_download_url").trim()
                if (name.isNotBlank() && url.isNotBlank()) {
                    requireGitHubAssetUrl(url)
                    add(GitHubAsset(name, url))
                }
            }
        }
        if (assets.isEmpty()) throw UpdateProtocolException("Release 没有有效 assets")
        return GitHubRelease(tagName, json.optBoolean("draft"), json.optBoolean("prerelease"), assets)
    }

    fun parseMetadata(
        jsonText: String,
        release: GitHubRelease,
        metadataAsset: GitHubAsset,
        apkAsset: GitHubAsset,
    ): UpdateCandidate {
        if (release.draft || release.prerelease) throw UpdateProtocolException("不接受 draft/prerelease Release")
        val json = runCatching { JSONObject(jsonText) }
            .getOrElse { throw UpdateProtocolException("update.json 无效") }
        val versionName = requiredString(json, "versionName")
        val versionCode = requiredLong(json, "versionCode", minimum = 1)
        val apkName = requiredString(json, "apkName")
        val apkUrl = requiredString(json, "apkUrl", allowEmpty = true)
        val sha256 = requiredString(json, "sha256").lowercase(Locale.US)
        if (!shaPattern.matches(sha256)) throw UpdateProtocolException("update.json sha256 无效")
        val forceUpdate = requiredBoolean(json, "forceUpdate")
        val minimumVersionCode = requiredLong(json, "minimumVersionCode", minimum = 0)
        val title = requiredString(json, "title")
        val notes = requiredStringArray(json, "notes")
        val apkSizeBytes = requiredLong(json, "apkSizeBytes", minimum = 1)

        val tagVersion = tagPattern.matchEntire(release.tagName)?.groupValues?.get(1)
            ?: throw UpdateProtocolException("Release tag 无效")
        if (versionName != tagVersion) throw UpdateProtocolException("tag 与 metadata versionName 不一致")
        if (apkName != expectedApkName(versionName) || !apkNamePattern.matches(apkName)) {
            throw UpdateProtocolException("APK 文件名不符合 ShineVoice Release 规范")
        }
        if (apkAsset.name != apkName) throw UpdateProtocolException("Release APK asset 名称不匹配")
        if (metadataAsset.name != "update.json") throw UpdateProtocolException("metadata asset 名称必须为 update.json")
        if (apkUrl.isNotBlank()) {
            requireGitHubAssetUrl(apkUrl)
            if (URI(apkUrl).path.orEmpty().substringAfterLast('/') != apkName) {
                throw UpdateProtocolException("metadata apkUrl 与 APK asset 不匹配")
            }
        }
        return UpdateCandidate(
            tagName = release.tagName,
            metadata = UpdateMetadata(
                versionName = versionName,
                versionCode = versionCode,
                apkName = apkName,
                apkUrl = apkUrl,
                sha256 = sha256,
                forceUpdate = forceUpdate,
                minimumVersionCode = minimumVersionCode,
                title = title,
                notes = notes,
                apkSizeBytes = apkSizeBytes,
            ),
            apkAsset = apkAsset,
            metadataAsset = metadataAsset,
        )
    }

    fun parseCandidate(releaseJson: String, metadataJson: String): UpdateCandidate {
        val release = parseRelease(releaseJson)
        val apk = release.assets.firstOrNull { it.name == expectedApkName(release.tagName) }
            ?: throw UpdateProtocolException("Release 缺少规范命名的 APK")
        val metadata = release.assets.firstOrNull { it.name == "update.json" }
            ?: throw UpdateProtocolException("Release 缺少 update.json")
        return parseMetadata(metadataJson, release, metadata, apk)
    }

    fun decide(localVersionCode: Long, candidate: UpdateCandidate): UpdateDecision =
        if (candidate.metadata.versionCode > localVersionCode) UpdateDecision.UpdateAvailable else UpdateDecision.Latest

    fun requiresImmediateUpdate(localVersionCode: Long, candidate: UpdateCandidate): Boolean =
        decide(localVersionCode, candidate) == UpdateDecision.UpdateAvailable &&
            (candidate.metadata.forceUpdate || localVersionCode < candidate.metadata.minimumVersionCode)

    fun isSafeApkName(name: String): Boolean = apkNamePattern.matches(name) &&
        !name.contains('/') && !name.contains('\\') && !name.contains("..")

    fun requireGitHubAssetUrl(url: String): String {
        val uri = runCatching { URI(url) }.getOrElse {
            throw UpdateProtocolException("GitHub asset URL 无效")
        }
        val host = uri.host?.lowercase(Locale.US)
        val githubHost = host == "github.com" || host == "objects.githubusercontent.com" ||
            host == "githubusercontent.com" || host?.endsWith(".githubusercontent.com") == true
        if (uri.scheme?.lowercase(Locale.US) != "https" || !githubHost || uri.path.isNullOrBlank()) {
            throw UpdateProtocolException("只允许 GitHub HTTPS asset URL")
        }
        return url
    }

    private fun requiredString(json: JSONObject, key: String, allowEmpty: Boolean = false): String {
        if (!json.has(key) || json.isNull(key) || json.opt(key) !is String) {
            throw UpdateProtocolException("update.json 缺少或错误字段：$key")
        }
        val value = json.getString(key).trim()
        if (!allowEmpty && value.isBlank()) throw UpdateProtocolException("update.json 字段为空：$key")
        return value
    }

    private fun requiredLong(json: JSONObject, key: String, minimum: Long): Long {
        val raw = json.opt(key)
        if (raw !is Number || raw.toDouble() != raw.toLong().toDouble()) {
            throw UpdateProtocolException("update.json 数字字段无效：$key")
        }
        val value = raw.toLong()
        if (value < minimum) throw UpdateProtocolException("update.json 数字字段越界：$key")
        return value
    }

    private fun requiredBoolean(json: JSONObject, key: String): Boolean {
        val raw = json.opt(key)
        if (raw !is Boolean) throw UpdateProtocolException("update.json 布尔字段无效：$key")
        return raw
    }

    private fun requiredStringArray(json: JSONObject, key: String): List<String> {
        val array = json.optJSONArray(key) ?: throw UpdateProtocolException("update.json 数组字段无效：$key")
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.opt(index)
                if (item !is String) throw UpdateProtocolException("update.json notes 必须全部为字符串")
                if (item.isNotBlank()) add(item.trim())
            }
        }
    }
}
