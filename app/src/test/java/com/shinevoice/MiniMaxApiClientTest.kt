package com.shinevoice

import com.shinevoice.domain.tts.TtsErrorCode
import com.shinevoice.provider.minimax.MiniMaxApiClient
import com.shinevoice.provider.minimax.MiniMaxException
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for the current MiniMax API JSON contract (get_voice /
 * files-upload / voice_clone / t2a_v2) — no external network needed except a
 * localhost socket for the timeout path.
 */
class MiniMaxApiClientTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val client = MiniMaxApiClient()

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
        assertEquals("我的声音", voices[0].name)
        assertEquals("svffffeeeeddddcc", voices[1].name) // blank description falls back to voice_id
    }

    @Test
    fun parseClonedVoicesAuthFailureThrowsBusinessError() {
        val error = runCatching {
            MiniMaxApiClient.parseClonedVoices("""{"base_resp":{"status_code":1004,"status_msg":"invalid api key"}}""")
        }.exceptionOrNull()!!
        val message = (error as com.shinevoice.provider.minimax.ApiException).userMessage
        assertTrue(message.contains("API Key 无效"))
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
        assertTrue((audio as MiniMaxApiClient.SynthesisAudio.HexBytes).decode().contentEquals(byteArrayOf(0x52, 0x49, 0x46, 0x46)))
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

    // ---------- timeout path against a real (localhost) silent socket ----------

    @Test
    fun connectTimeoutMapsToGenerationTimeout(): Unit = runBlocking {
        // A socket that accepts but never answers exercises the read timeout.
        val server = ServerSocket(0)
        val thread = Thread {
            while (!server.isClosed) {
                runCatching {
                    val socket = server.accept()
                    // Hold the connection open without writing a response.
                    Thread.sleep(TimeUnit.SECONDS.toMillis(30))
                    runCatching { socket.close() }
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        try {
            val fastClient = MiniMaxApiClient(connectTimeoutMs = 500, readTimeoutMs = 500)
            val result = fastClient.listVoices(
                apiKey = "sk-test-not-a-real-key",
                baseUrl = "http://127.0.0.1:${server.localPort}/v1/",
                groupId = null,
            )
            val error = result.exceptionOrNull() as MiniMaxException
            assertEquals(TtsErrorCode.GenerationTimeout, error.error.code)
            assertTrue(error.error.userMessage.contains("超时"))
        } finally {
            runCatching { server.close() }
        }
    }

    @Test
    fun uploadRejectsOversizedFileLocally(): Unit = runBlocking {
        val big = tmp.newFile("big.wav").apply { writeBytes(ByteArray(20 * 1024 * 1024 + 1)) }
        val result = client.uploadReferenceAudio(
            apiKey = "sk-test",
            baseUrl = "https://api.minimax.cn/v1/",
            groupId = null,
            audioFile = big,
        )
        val error = result.exceptionOrNull() as MiniMaxException
        assertEquals(TtsErrorCode.InvalidReferenceAudio, error.error.code)
        assertTrue(error.error.userMessage.contains("20 MB"))
    }

    @Test
    fun uploadRejectsMissingFileLocally(): Unit = runBlocking {
        val result = client.uploadReferenceAudio(
            apiKey = "sk-test",
            baseUrl = "https://api.minimax.cn/v1/",
            groupId = null,
            audioFile = File(tmp.root, "missing.wav"),
        )
        val error = result.exceptionOrNull() as MiniMaxException
        assertEquals(TtsErrorCode.InvalidReferenceAudio, error.error.code)
    }

    @Test
    fun cloneRejectsMalformedVoiceIdBeforeNetwork(): Unit = runBlocking {
        val result = client.cloneVoice(
            apiKey = "sk-test",
            baseUrl = "https://api.minimax.cn/v1/",
            groupId = null,
            fileId = 123L,
            voiceId = "1_bad",
        )
        val error = result.exceptionOrNull() as MiniMaxException
        assertEquals(TtsErrorCode.ApiServerError, error.error.code)
        assertTrue(error.error.userMessage.contains("音色标识格式不正确"))
    }

    /** Mirrors MiniMaxProvider.generateVoiceId without instantiating Android deps. */
    private fun MiniMaxProvider_generateVoiceId(): String {
        val bytes = ByteArray(8)
        java.security.SecureRandom().nextBytes(bytes)
        return "sv" + bytes.joinToString("") { "%02x".format(it) }
    }
}
