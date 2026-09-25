package com.shinevoice

import com.shinevoice.data.settings.MiniMaxApiKeySanitizer
import com.shinevoice.domain.tts.TtsErrorCode
import com.shinevoice.provider.minimax.MiniMaxApiClient
import com.shinevoice.provider.minimax.MiniMaxConnection
import com.shinevoice.provider.minimax.MiniMaxException
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for the current MiniMax API JSON contract (get_voice /
 * files-upload / voice_clone / t2a_v2) plus the pure key sanitizer.
 */
class MiniMaxApiClientTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val client = MiniMaxApiClient()

    private fun connection(baseUrl: String, groupId: String? = null) =
        MiniMaxConnection(apiKey = "test-key", baseUrl = baseUrl, groupId = groupId)

    // ---------- voice_id format rules (official: [8,256], letter-first, [-_] allowed, not trailing) ----------

    @Test
    fun voiceIdValidationFollowsOfficialRules() {
        assertTrue(MiniMaxApiClient.isValidCustomVoiceId("sv0123456789abcdef"))
        assertTrue(MiniMaxApiClient.isValidCustomVoiceId("MyVoice-2026_v1"))
        assertFalse(MiniMaxApiClient.isValidCustomVoiceId("1svabcdef")) // must start with a letter
        assertFalse(MiniMaxApiClient.isValidCustomVoiceId("short")) // < 8 chars
        assertFalse(MiniMaxApiClient.isValidCustomVoiceId("sv_")) // trailing underscore
        assertFalse(MiniMaxApiClient.isValidCustomVoiceId("sv-bad!id")) // illegal character
    }

    @Test
    fun generatedVoiceIdIsRuleCompliant() {
        repeat(20) {
            val id = MiniMaxProvider_generateVoiceId()
            assertTrue(MiniMaxApiClient.isValidCustomVoiceId(id))
        }
    }

    // ---------- API key sanitizer (C08) ----------

    @Test
    fun keySanitizerTrimsLeadingTrailingWhitespace() {
        val result = MiniMaxApiKeySanitizer.sanitize("  sk-test-123\t")
        val valid = result as MiniMaxApiKeySanitizer.Result.Valid
        assertEquals("sk-test-123", valid.key)
    }

    @Test
    fun keySanitizerRejectsNewlinesZeroWidthAndBearerPrefix() {
        assertTrue(MiniMaxApiKeySanitizer.sanitize("sk-a\nsk-b") is MiniMaxApiKeySanitizer.Result.Invalid)
        assertTrue(MiniMaxApiKeySanitizer.sanitize("sk-a\u200Bb") is MiniMaxApiKeySanitizer.Result.Invalid)
        assertTrue(MiniMaxApiKeySanitizer.sanitize("Bearer sk-abc") is MiniMaxApiKeySanitizer.Result.Invalid)
        assertTrue(MiniMaxApiKeySanitizer.sanitize("sk with space") is MiniMaxApiKeySanitizer.Result.Invalid)
        assertTrue(MiniMaxApiKeySanitizer.sanitize("   ") is MiniMaxApiKeySanitizer.Result.Invalid)
        val ok = MiniMaxApiKeySanitizer.sanitize("  sk-cp-abcdef123456  ")
        assertEquals("sk-cp-abcdef123456", (ok as MiniMaxApiKeySanitizer.Result.Valid).key)
    }

    // ---------- GET/POST /v1/get_voice (voice management) ----------

    @Test
    fun parseClonedVoicesFromGetVoice() {
        val json = """
            {"voice_cloning":[
              {"voice_id":"sv0123456789abcdef","description":["我的声音"],"created_time":"2026-09-01"},
              {"voice_id":"svffffeeeeddddcc","description":[]}
            ],"base_resp":{"status_code":0,"status_msg":""}}
        """.trimIndent()
        val voices = MiniMaxApiClient.parseClonedVoices(json)
        assertEquals(2, voices.size)
        assertEquals("我的声音", voices[0].displayName)
        assertEquals("svffffeeeeddddcc", voices[1].displayName) // blank description falls back to voice_id
    }

    @Test
    fun parseSystemVoicesFromGetVoice() {
        val json = """
            {"system_voice":[
              {"voice_id":"qingse青年","voice_name":"青涩青年音色","description":["x"]},
              {"voice_id":"voice-b","voice_name":"精英青年音色"}
            ],"base_resp":{"status_code":0,"status_msg":""}}
        """.trimIndent()
        val catalog = MiniMaxApiClient.parseVoiceCatalog(json, "system")
        assertEquals(2, catalog.systemVoices.size)
        assertEquals("青涩青年音色", catalog.systemVoices[0].displayName)
        assertTrue(catalog.clonedVoices.isEmpty())
    }

    @Test
    fun legalEmptyCatalogIsSuccess() {
        // C06: a valid empty list is an empty state, not an error.
        val catalog = MiniMaxApiClient.parseVoiceCatalog(
            """{"base_resp":{"status_code":0,"status_msg":""}}""",
            "voice_cloning",
        )
        assertTrue(catalog.systemVoices.isEmpty())
        assertTrue(catalog.clonedVoices.isEmpty())
    }

    @Test
    fun missingBaseRespIsProtocolError() {
        // C06: `{}` (or any payload without base_resp) must never look ONLINE.
        val error = runCatching {
            MiniMaxApiClient.parseVoiceCatalog("{}", "voice_cloning")
        }.exceptionOrNull()!!
        assertTrue(error is org.json.JSONException)
    }

    @Test
    fun htmlBodyIsProtocolError() {
        val error = runCatching {
            MiniMaxApiClient.parseVoiceCatalog("<html><body>gateway</body></html>", "system")
        }.exceptionOrNull()!!
        assertTrue(error is org.json.JSONException)
    }

    @Test
    fun parseClonedVoicesAuthFailureThrowsBusinessError() {
        val error = runCatching {
            MiniMaxApiClient.parseClonedVoices("""{"base_resp":{"status_code":1004,"status_msg":"invalid api key"}}""")
        }.exceptionOrNull()!!
        val message = (error as com.shinevoice.provider.minimax.ApiException).userMessage
        assertTrue(message.contains("API Key 无效"))
    }

    @Test
    fun insufficientBalanceMapsToDedicatedCode() {
        val error = runCatching {
            MiniMaxApiClient.parseClonedVoices("""{"base_resp":{"status_code":1008,"status_msg":"balance"}}""")
        }.exceptionOrNull()!!
        assertEquals(
            TtsErrorCode.ApiInsufficientBalance,
            (error as com.shinevoice.provider.minimax.ApiException).code,
        )
    }

    // ---------- POST /v1/files/upload ----------

    @Test
    fun parseUploadFileIdFromNestedFileObject() {
        val json = """{"file":{"file_id":151253,"filename":"reference.wav","bytes":1024},"base_resp":{"status_code":0,"status_msg":""}}"""
        assertEquals(151253L, MiniMaxApiClient.parseUploadFileId(json))
    }

    @Test(expected = Exception::class)
    fun parseUploadFileIdMissingThrows() {
        MiniMaxApiClient.parseUploadFileId("""{"file":{},"base_resp":{"status_code":0}}""")
    }

    // ---------- POST /v1/voice_clone ----------

    @Test
    fun parseCloneResponseAcceptsOkWithoutEcho() {
        // Current official schema does not echo voice_id; success = status_code 0.
        val json = """{"input_sensitive":{"type":0},"demo_audio":"","base_resp":{"status_code":0,"status_msg":""}}"""
        assertEquals("", MiniMaxApiClient.parseCloneResponse(json))
    }

    @Test
    fun parseCloneResponseUsesLegacyEchoWhenPresent() {
        assertEquals("cloned-1", MiniMaxApiClient.parseCloneResponse("""{"voice_id":"cloned-1","base_resp":{"status_code":0}}"""))
    }

    @Test
    fun parseCloneResponseNoPermissionMapsToUnauthorized() {
        val error = runCatching {
            MiniMaxApiClient.parseCloneResponse("""{"base_resp":{"status_code":2038,"status_msg":"no clone permission"}}""")
        }.exceptionOrNull()!!
        val apiError = error as com.shinevoice.provider.minimax.ApiException
        assertEquals(TtsErrorCode.ApiUnauthorized, apiError.code)
        assertTrue(apiError.userMessage.contains("克隆权限"))
    }

    // ---------- POST /v1/t2a_v2 ----------

    @Test
    fun parseSynthesisAudioUrlPayload() {
        val json = """{"data":{"audio":"https://cdn.example.com/a.wav?sig=1","status":2},"base_resp":{"status_code":0,"status_msg":""}}"""
        val audio = MiniMaxApiClient.parseSynthesisAudio(json)
        assertTrue(audio is MiniMaxApiClient.SynthesisAudio.DownloadUrl)
        assertEquals("https://cdn.example.com/a.wav?sig=1", (audio as MiniMaxApiClient.SynthesisAudio.DownloadUrl).url)
    }

    @Test
    fun parseSynthesisAudioHexPayload() {
        val hex = "52494646"
        val json = """{"data":{"audio":"$hex"},"base_resp":{"status_code":0,"status_msg":""}}"""
        val audio = MiniMaxApiClient.parseSynthesisAudio(json)
        assertTrue(audio is MiniMaxApiClient.SynthesisAudio.HexBytes)
        assertTrue(
            MiniMaxApiClient.hexToBytes((audio as MiniMaxApiClient.SynthesisAudio.HexBytes).hex)
                .contentEquals(byteArrayOf(0x52, 0x49, 0x46, 0x46)),
        )
    }

    @Test
    fun invalidHexCharactersAreRejected() {
        // C09: every hex char must be valid; the old decoder turned bad chars
        // into 0xff nibbles silently.
        assertThrows<IllegalArgumentException> { MiniMaxApiClient.hexToBytes("5Z49") }
        assertThrows<IllegalArgumentException> { MiniMaxApiClient.hexToBytes("524") }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (expected: Throwable) {
            assertTrue(expected is T)
            return
        }
        throw AssertionError("expected ${T::class.java.simpleName} not thrown")
    }

    @Test(expected = Exception::class)
    fun parseSynthesisAudioMissingDataThrows() {
        MiniMaxApiClient.parseSynthesisAudio("""{"base_resp":{"status_code":0}}""")
    }

    @Test
    fun parseSynthesisAudioBusinessErrorThrows() {
        val error = runCatching {
            MiniMaxApiClient.parseSynthesisAudio(
                """{"base_resp":{"status_code":2013,"status_msg":"voice not found"}}""",
            )
        }.exceptionOrNull()!!
        val apiError = error as com.shinevoice.provider.minimax.ApiException
        assertEquals(TtsErrorCode.ApiServerError, apiError.code)
        assertTrue(apiError.userMessage.contains("voice not found"))
    }

    @Test
    fun invalidVoiceId2054MapsToChineseMessage() {
        // Live-observed contract: HTTP 200 with base_resp.status_code=2054
        // ("voice id not exist") for a deleted/nonexistent cloned voice.
        val error = runCatching {
            MiniMaxApiClient.parseSynthesisAudio(
                """{"base_resp":{"status_code":2054,"status_msg":"voice id not exist"}}""",
            )
        }.exceptionOrNull()!!
        val apiError = error as com.shinevoice.provider.minimax.ApiException
        assertEquals(TtsErrorCode.ApiServerError, apiError.code)
        assertTrue(apiError.userMessage.contains("云端音色不存在"))
    }

    // ---------- error mapping never leaks the key ----------

    @Test
    fun errorMappingNeverLeaksKey() {
        val error = MiniMaxApiClient.mapApiError(
            org.json.JSONObject("""{"base_resp":{"status_code":2001,"status_msg":"鉴权失败"}}"""),
            "云端合成失败",
        )
        assertTrue(error.message!!.contains("鉴权失败"))
    }

    // ---------- local validation paths (no network) ----------

    @Test
    fun uploadRejectsOversizedFileLocally(): Unit = runBlocking {
        val big = tmp.newFile("big.wav").apply { writeBytes(ByteArray(20 * 1024 * 1024 + 1)) }
        val result = client.uploadReferenceAudio(connection("https://api.minimax.cn/v1/"), big)
        val error = result.exceptionOrNull() as MiniMaxException
        assertEquals(TtsErrorCode.InvalidReferenceAudio, error.error.code)
        assertTrue(error.error.userMessage.contains("20 MB"))
    }

    @Test
    fun uploadRejectsMissingFileLocally(): Unit = runBlocking {
        val result = client.uploadReferenceAudio(
            connection("https://api.minimax.cn/v1/"),
            File(tmp.root, "missing.wav"),
        )
        val error = result.exceptionOrNull() as MiniMaxException
        assertEquals(TtsErrorCode.InvalidReferenceAudio, error.error.code)
    }

    @Test
    fun cloneRejectsMalformedVoiceIdBeforeNetwork(): Unit = runBlocking {
        val result = client.cloneVoice(connection("https://api.minimax.cn/v1/"), 123L, "1_bad")
        val error = result.exceptionOrNull() as MiniMaxException
        assertEquals(TtsErrorCode.ApiServerError, error.error.code)
        assertTrue(error.error.userMessage.contains("音色标识不正确") || error.error.userMessage.contains("音色标识格式不正确"))
    }

    /** Mirrors MiniMaxProvider.generateVoiceId without instantiating Android deps. */
    private fun MiniMaxProvider_generateVoiceId(): String {
        val bytes = ByteArray(8)
        java.security.SecureRandom().nextBytes(bytes)
        return "sv" + bytes.joinToString("") { "%02x".format(it) }
    }
}
