package com.shinevoice

import com.shinevoice.domain.tts.TtsErrorCode
import com.shinevoice.provider.minimax.MiniMaxApiClient
import com.shinevoice.provider.minimax.MiniMaxConnection
import com.shinevoice.provider.minimax.MiniMaxException
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end request tests against an injectable MockWebServer: real OkHttp
 * transport, real URL building, real error classification. Covers the C01–C10
 * contract matrix that is verifiable on the JVM.
 */
class MiniMaxApiContractTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: MiniMaxApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = MiniMaxApiClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun connection(groupId: String? = null) = MiniMaxConnection(
        apiKey = "sk-test-key",
        baseUrl = server.url("/").toString().trimEnd('/'),
        groupId = groupId,
    )

    private fun okSystemCatalog(count: Int = 1): String {
        val voices = (1..count).joinToString(",") {
            """{"voice_id":"sys-$it","voice_name":"官方音色$it","description":[]}"""
        }
        return """{"system_voice":[$voices],"base_resp":{"status_code":0,"status_msg":"success"}}"""
    }

    // C01: valid account, no cloned voices -> system catalog succeeds, official
    // voices are selectable; empty cloned list is a legal empty state.
    @Test
    fun systemCatalogWorksWhenAccountHasNoClonedVoices(): Unit = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"system_voice":[{"voice_id":"sys-1","voice_name":"官方音色1"}],"base_resp":{"status_code":0,"status_msg":"success"}}""",
            ).setHeader("Content-Type", "application/json"),
        )
        val result = client.listVoices(connection(), MiniMaxApiClient.VOICE_TYPE_SYSTEM)
        val catalog = result.getOrThrow()
        assertEquals(1, catalog.systemVoices.size)
        val recorded = server.takeRequest()
        assertEquals("/v1/get_voice", recorded.path)
        assertEquals("Bearer sk-test-key", recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains("\"voice_type\":\"system\""))
    }

    // C02: invalid key -> explicit auth signal, never a generic Unknown.
    @Test
    fun unauthorizedJsonMapsToApiUnauthorized(): Unit = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"base_resp":{"status_code":1004,"status_msg":"invalid api key"}}""",
            ).setHeader("Content-Type", "application/json"),
        )
        val error = client.listVoices(connection()).exceptionOrNull() as MiniMaxException
        assertEquals(TtsErrorCode.ApiUnauthorized, error.error.code)
        assertTrue(error.error.userMessage.contains("API Key 无效"))
    }

    // C05: HTTP 401 without JSON body still classifies by HTTP status.
    @Test
    fun http401EmptyBodyMapsToApiUnauthorized(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody(""))
        val error = client.listVoices(connection()).exceptionOrNull() as MiniMaxException
        assertEquals(TtsErrorCode.ApiUnauthorized, error.error.code)
    }

    // C05: 502 HTML must classify as server error/protocol, never ONLINE.
    @Test
    fun http502HtmlMapsToServerError(): Unit = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(502).setBody("<html><body>Bad Gateway</body></html>"),
        )
        val error = client.listVoices(connection()).exceptionOrNull() as MiniMaxException
        assertEquals(TtsErrorCode.ApiServerError, error.error.code)
    }

    // C05: HTTP 200 with an empty body is a protocol error, not success.
    @Test
    fun http200EmptyBodyIsProtocolError(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val error = client.listVoices(connection()).exceptionOrNull() as MiniMaxException
        assertEquals(TtsErrorCode.ApiProtocolError, error.error.code)
    }

    // C05: HTTP 200 with an HTML (WAF) page is a protocol error with a hint.
    @Test
    fun http200HtmlBodyIsProtocolErrorWithGatewayHint(): Unit = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("<html><script>challenge</script></html>")
                .setHeader("Content-Type", "text/html"),
        )
        val error = client.listVoices(connection()).exceptionOrNull() as MiniMaxException
        assertEquals(TtsErrorCode.ApiProtocolError, error.error.code)
        assertTrue(error.error.userMessage.contains("网关") || error.error.userMessage.contains("JSON"))
    }

    // C05: HTTP 200 with `{}` (missing base_resp) is a protocol error.
    @Test
    fun http200EmptyJsonIsProtocolError(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val error = client.listVoices(connection()).exceptionOrNull() as MiniMaxException
        assertEquals(TtsErrorCode.ApiProtocolError, error.error.code)
    }

    // C03: 200 + business 1008 keeps the error actionable.
    @Test
    fun businessInsufficientBalanceMapsToDedicatedCode(): Unit = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"base_resp":{"status_code":1008,"status_msg":"insufficient balance"}}""",
            ).setHeader("Content-Type", "application/json"),
        )
        val error = client.listVoices(connection()).exceptionOrNull() as MiniMaxException
        assertEquals(TtsErrorCode.ApiInsufficientBalance, error.error.code)
        assertTrue(error.error.userMessage.contains("余额不足"))
    }

    // C04: cloned catalog failing must not take down the system catalog; the
    // provider layers them (see provider test below), here we verify the client
    // reports each independently.
    @Test
    fun clonedCatalogFailureIsIndependent(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(okSystemCatalog(2)).setHeader("Content-Type", "application/json"))
        server.enqueue(
            MockResponse().setBody(
                """{"base_resp":{"status_code":2038,"status_msg":"no clone permission"}}""",
            ).setHeader("Content-Type", "application/json"),
        )
        val system = client.listVoices(connection(), MiniMaxApiClient.VOICE_TYPE_SYSTEM).getOrThrow()
        val cloned = client.listVoices(connection(), MiniMaxApiClient.VOICE_TYPE_CLONED)
        assertEquals(2, system.systemVoices.size)
        val error = cloned.exceptionOrNull() as MiniMaxException
        assertEquals(TtsErrorCode.ApiUnauthorized, error.error.code)
    }

    // C09: hex synthesis happy path writes a verified WAV atomically.
    @Test
    fun synthesisHexWritesValidatedWavAtomically(): Unit = runBlocking {
        val wav = validWavBytes(24000, 2400) // 100 ms
        val json = """{"data":{"audio":"${wav.toHex()}"},"base_resp":{"status_code":0,"status_msg":"success"}}"""
        server.enqueue(MockResponse().setBody(json).setHeader("Content-Type", "application/json"))
        val output = File(tmp.root, "out.wav")
        val result = client.synthesizeToFile(
            connection = connection(),
            voiceId = "sys-1",
            text = "测试",
            speed = 1.0f,
            outputFile = output,
        )
        assertEquals(output, result.getOrThrow())
        assertTrue(output.isFile)
        assertEquals(wav.size.toLong(), output.length())
        assertFalse(File(tmp.root, "out.wav.part").exists())
        val recorded = server.takeRequest()
        assertEquals("/v1/t2a_v2", recorded.path)
    }

    // C09: non-audio hex payload (MP3 magic bytes masquerading as WAV) is
    // rejected by the post-download structure check and never committed.
    @Test
    fun synthesisGarbageHexIsRejectedNotSaved(): Unit = runBlocking {
        val mp3Magic = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00.toByte()) + ByteArray(200)
        val json = """{"data":{"audio":"${mp3Magic.toHex()}"},"base_resp":{"status_code":0,"status_msg":"success"}}"""
        server.enqueue(MockResponse().setBody(json).setHeader("Content-Type", "application/json"))
        val output = File(tmp.root, "bad.wav")
        val error = client.synthesizeToFile(
            connection = connection(),
            voiceId = "sys-1",
            text = "测试",
            speed = 1.0f,
            outputFile = output,
        ).exceptionOrNull() as MiniMaxException
        assertEquals(TtsErrorCode.ApiProtocolError, error.error.code)
        assertFalse(output.exists())
        assertFalse(File(tmp.root, "bad.wav.part").exists())
    }

    // C09: invalid hex characters are rejected before writing anything.
    @Test
    fun synthesisInvalidHexRejected(): Unit = runBlocking {
        val json = """{"data":{"audio":"not-hex!!"},"base_resp":{"status_code":0,"status_msg":"success"}}"""
        server.enqueue(MockResponse().setBody(json).setHeader("Content-Type", "application/json"))
        val output = File(tmp.root, "bad2.wav")
        val result = client.synthesizeToFile(
            connection = connection(),
            voiceId = "sys-1",
            text = "测试",
            speed = 1.0f,
            outputFile = output,
        )
        assertTrue(result.isFailure)
        assertFalse(output.exists())
    }

    // C07: cancelling the coroutine aborts the in-flight request.
    @Test
    fun cancellationAbortsInFlightRequest(): Unit = runBlocking {
        server.enqueue(MockResponse().setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS).setBody("{}"))
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch(kotlinx.coroutines.Dispatchers.IO) {
            started.complete(Unit)
            client.listVoices(connection())
        }
        started.await()
        job.cancel()
        // The call returns promptly; no hang and no zombie state write.
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
    }

    // GroupId goes through canonical query encoding.
    @Test
    fun groupIdIsCanonicallyEncodedInUrl(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(okSystemCatalog()).setHeader("Content-Type", "application/json"))
        client.listVoices(connection(groupId = "18 39/x"))
        val path = server.takeRequest().path ?: ""
        assertTrue(path.contains("GroupId=18%2039%2Fx"))
    }

    // C10-adjacent: voice_clone success echoes no id; provider keeps requested id.
    @Test
    fun cloneSuccessWithoutEchoReturnsBlank(): Unit = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"input_sensitive":{"type":0},"base_resp":{"status_code":0,"status_msg":""}}""",
            ).setHeader("Content-Type", "application/json"),
        )
        val echoed = client.cloneVoice(connection(), 42L, "sv0123456789abcdef").getOrThrow()
        assertEquals("", echoed)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"file_id\":42"))
        assertTrue(body.contains("\"voice_id\":\"sv0123456789abcdef\""))
    }

    @Test
    fun uploadPostsMultipartWithPurpose(): Unit = runBlocking {
        val wav = validWavBytes(24000, 10 * 24000) // 10 s: passes the duration precheck
        val file = tmp.newFile("reference.wav").apply { writeBytes(wav) }
        server.enqueue(
            MockResponse().setBody(
                """{"file":{"file_id":7},"base_resp":{"status_code":0,"status_msg":""}}""",
            ).setHeader("Content-Type", "application/json"),
        )
        val fileId = client.uploadReferenceAudio(connection(), file).getOrThrow()
        assertEquals(7L, fileId)
        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("name=\"purpose\""))
        assertTrue(body.contains("voice_clone"))
        assertTrue(recorded.getHeader("Authorization")?.startsWith("Bearer ") == true)
    }

    @Test
    fun timeoutMapsToGenerationTimeout(): Unit = runBlocking {
        val fastClient = MiniMaxApiClient(
            OkHttpClient.Builder()
                .connectTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build(),
        )
        server.enqueue(MockResponse().setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS).setBody("{}"))
        val error = fastClient.listVoices(connection()).exceptionOrNull() as MiniMaxException
        assertEquals(TtsErrorCode.GenerationTimeout, error.error.code)
    }

    // ---------- helpers ----------

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun validWavBytes(sampleRate: Int, sampleCount: Int): ByteArray {
        val data = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            val v = (1000 * kotlin.math.sin(i * 0.05)).toInt()
            data[i * 2] = (v and 0xFF).toByte()
            data[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val header = wavHeader(sampleRate, data.size)
        return header + data
    }

    private fun wavHeader(sampleRate: Int, dataSize: Int): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        return buffer.array()
    }
}
