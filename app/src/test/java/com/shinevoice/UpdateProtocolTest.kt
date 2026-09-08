package com.shinevoice

import com.shinevoice.update.UpdateDecision
import com.shinevoice.update.UpdateProtocol
import com.shinevoice.update.UpdateProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateProtocolTest {
    @Test
    fun validStableReleaseParsesAndOffersOnlyHigherVersionCode() {
        val candidate = candidate()
        assertEquals("1.0.1", candidate.metadata.versionName)
        assertEquals(1000001L, candidate.metadata.versionCode)
        assertEquals(UpdateDecision.UpdateAvailable, UpdateProtocol.decide(1000000L, candidate))
        assertEquals(UpdateDecision.Latest, UpdateProtocol.decide(1000001L, candidate))
        assertEquals(UpdateDecision.Latest, UpdateProtocol.decide(1000002L, candidate))
    }

    @Test
    fun forceUpdateAndMinimumVersionAreParsedWithoutAllowingDowngrade() {
        val candidate = candidate(forceUpdate = true, minimumVersionCode = 1000001L)
        assertTrue(candidate.metadata.forceUpdate)
        assertEquals(1000001L, candidate.metadata.minimumVersionCode)
        assertTrue(UpdateProtocol.requiresImmediateUpdate(1000000L, candidate))
        assertEquals(UpdateDecision.Latest, UpdateProtocol.decide(1000002L, candidate))
    }

    @Test
    fun draftAndPrereleaseAreRejected() {
        assertFails { candidate(draft = true) }
        assertFails { candidate(prerelease = true) }
    }

    @Test
    fun missingAssetsAndWrongAssetNameAreRejected() {
        assertFails { candidate(includeMetadata = false) }
        assertFails { candidate(includeApk = false) }
        assertFails { candidate(apkName = "wrong.apk") }
    }

    @Test
    fun malformedMetadataIsRejected() {
        assertFails { candidate(sha256 = "abc") }
        assertFails { candidate(versionName = "1.0") }
        assertFails { candidate(versionCode = 0L) }
        assertFails { candidate(apkUrl = "http://evil.example/app.apk") }
        assertFails { candidate(tag = "release-1.0.1") }
    }

    @Test
    fun githubAssetHostValidationIsStrict() {
        UpdateProtocol.requireGitHubAssetUrl("https://github.com/anjingdtl/ShineVoice/releases/download/V1.0.1/app.apk")
        UpdateProtocol.requireGitHubAssetUrl("https://objects.githubusercontent.com/download/app.apk")
        assertFails { UpdateProtocol.requireGitHubAssetUrl("http://github.com/app.apk") }
        assertFails { UpdateProtocol.requireGitHubAssetUrl("https://example.com/app.apk") }
        assertFails { UpdateProtocol.requireGitHubAssetUrl("https://github.com.evil.example/app.apk") }
    }

    private fun candidate(
        tag: String = "V1.0.1",
        versionName: String = "1.0.1",
        versionCode: Long = 1000001L,
        sha256: String = "a".repeat(64),
        apkName: String = "ShineVoice-V1.0.1-release.apk",
        apkUrl: String = "",
        forceUpdate: Boolean = false,
        minimumVersionCode: Long = 0L,
        draft: Boolean = false,
        prerelease: Boolean = false,
        includeMetadata: Boolean = true,
        includeApk: Boolean = true,
    ) = UpdateProtocol.parseCandidate(
        releaseJson(
            tag = tag,
            draft = draft,
            prerelease = prerelease,
            apkName = apkName,
            includeMetadata = includeMetadata,
            includeApk = includeApk,
        ),
        metadataJson(
            versionName = versionName,
            versionCode = versionCode,
            apkName = apkName,
            apkUrl = apkUrl,
            sha256 = sha256,
            forceUpdate = forceUpdate,
            minimumVersionCode = minimumVersionCode,
        ),
    )

    private fun releaseJson(
        tag: String,
        draft: Boolean,
        prerelease: Boolean,
        apkName: String,
        includeMetadata: Boolean,
        includeApk: Boolean,
    ): String {
        val assets = buildList {
            if (includeApk) add(asset(apkName))
            if (includeMetadata) add(asset("update.json"))
        }.joinToString(",")
        return """
            {
              "tag_name":"$tag",
              "draft":$draft,
              "prerelease":$prerelease,
              "assets":[$assets]
            }
        """.trimIndent()
    }

    private fun metadataJson(
        versionName: String,
        versionCode: Long,
        apkName: String,
        apkUrl: String,
        sha256: String,
        forceUpdate: Boolean,
        minimumVersionCode: Long,
    ): String = """
        {
          "versionName":"$versionName",
          "versionCode":$versionCode,
          "apkName":"$apkName",
          "apkUrl":"$apkUrl",
          "sha256":"$sha256",
          "forceUpdate":$forceUpdate,
          "minimumVersionCode":$minimumVersionCode,
          "title":"ShineVoice V$versionName",
          "notes":["多语言语音生成"],
          "apkSizeBytes":12345
        }
    """.trimIndent()

    private fun asset(name: String): String =
        "{\"name\":\"$name\",\"browser_download_url\":\"https://github.com/anjingdtl/ShineVoice/releases/download/V1.0.1/$name\"}"

    private fun assertFails(block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("expected UpdateProtocolException, got $error", error is UpdateProtocolException)
    }
}
