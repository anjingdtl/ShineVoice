package com.shinevoice

import com.shinevoice.provider.minimax.MiniMaxApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the MiniMax JSON contract parsers (no network needed). */
class MiniMaxApiClientTest {
    @Test
    fun parseVoiceListTopLevel() {
        val json = """
            {"voice_list":[{"voice_id":"v1","voice_name":"我的声音"},{"voice_id":"v2","voice_name":""}]}
        """.trimIndent()
        val voices = MiniMaxApiClient.parseVoiceList(json)
        assertEquals(2, voices.size)
        assertEquals("v1", voices[0].voiceId)
        assertEquals("我的声音", voices[0].name)
        assertEquals("v2", voices[1].name) // blank name falls back to voice_id
    }

    @Test
    fun parseVoiceListNestedData() {
        val json = """{"data":{"voice_list":[{"voice_id":"abc","voice_name":"旁白"}]}}"""
        val voices = MiniMaxApiClient.parseVoiceList(json)
        assertEquals(1, voices.size)
        assertEquals("旁白", voices.single().name)
    }

    @Test
    fun parseCloneVoiceIdTopLevel() {
        assertEquals("cloned-1", MiniMaxApiClient.parseCloneVoiceId("""{"voice_id":"cloned-1"}"""))
    }

    @Test
    fun parseCloneVoiceIdNested() {
        assertEquals("cloned-2", MiniMaxApiClient.parseCloneVoiceId("""{"data":{"voice_id":"cloned-2"}}"""))
    }

    @Test
    fun parseSynthesisAudioOk() {
        val base64 = "U0hJTkVWT0lDRQ=="
        val json = """{"base_resp":{"status_code":0},"data":{"audio":"$base64"}}"""
        assertEquals(base64, MiniMaxApiClient.parseSynthesisAudio(json))
    }

    @Test(expected = Exception::class)
    fun parseSynthesisAudioMissingDataThrows() {
        MiniMaxApiClient.parseSynthesisAudio("""{"base_resp":{"status_code":0}}""")
    }

    @Test(expected = Exception::class)
    fun parseSynthesisAudioErrorRespThrows() {
        MiniMaxApiClient.parseSynthesisAudio(
            """{"base_resp":{"status_code":1004,"status_msg":"请检查参数"}}""",
        )
    }

    @Test
    fun errorMappingNeverLeaksKey() {
        // Ensure the client maps an API error to a business message that does
        // not echo credentials back to the UI.
        val error = MiniMaxApiClient.mapApiError(
            org.json.JSONObject("""{"base_resp":{"status_code":2001,"status_msg":"鉴权失败"}}"""),
            "云端合成失败",
        )
        assertTrue(error.message!!.contains("鉴权失败"))
    }
}