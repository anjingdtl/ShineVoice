package com.shinevoice

import com.shinevoice.domain.tts.TtsLanguageCatalog
import com.shinevoice.provider.minimax.MiniMaxApiClient
import com.shinevoice.provider.minimax.MiniMaxLanguageMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsLanguageTest {
    @Test
    fun catalogUsesStableBcp47IdsAndHumanLabels() {
        assertEquals("zh-CN", TtsLanguageCatalog.ZH_CN.id)
        assertEquals("中文", TtsLanguageCatalog.ZH_CN.displayName)
        assertEquals("en-US", TtsLanguageCatalog.EN_US.id)
        assertEquals("English", TtsLanguageCatalog.EN_US.nativeName)
        assertEquals("ja-JP", TtsLanguageCatalog.JA_JP.id)
        assertEquals("日本語", TtsLanguageCatalog.JA_JP.nativeName)
    }

    @Test
    fun minimaxLanguageMappingFollowsOfficialLanguageBoostValues() {
        assertEquals("Chinese", MiniMaxLanguageMapper.toLanguageBoost("zh-CN"))
        assertEquals("Chinese,Yue", MiniMaxLanguageMapper.toLanguageBoost("zh-HK"))
        assertEquals("English", MiniMaxLanguageMapper.toLanguageBoost("en-US"))
        assertEquals("English", MiniMaxLanguageMapper.toLanguageBoost("en-GB"))
        assertEquals("Japanese", MiniMaxLanguageMapper.toLanguageBoost("ja-JP"))
        assertEquals("Korean", MiniMaxLanguageMapper.toLanguageBoost("ko-KR"))
        assertEquals("auto", MiniMaxLanguageMapper.toLanguageBoost("auto"))
        assertFalse(MiniMaxLanguageMapper.supportedLanguageIds.contains("xx-XX"))
    }

    @Test
    fun minimaxPayloadKeepsVoiceIdIndependentFromLanguageAndSpeed() {
        val voiceId = "sv_same_voice_for_all_languages"
        val chinese = MiniMaxApiClient.buildSynthesisPayload(
            model = MiniMaxApiClient.DEFAULT_MODEL,
            text = "你好",
            voiceId = voiceId,
            speed = 0.75f,
            languageBoost = "Chinese",
        )
        val english = MiniMaxApiClient.buildSynthesisPayload(
            model = MiniMaxApiClient.DEFAULT_MODEL,
            text = "Hello",
            voiceId = voiceId,
            speed = 1.5f,
            languageBoost = "English",
        )
        assertEquals(voiceId, chinese.getJSONObject("voice_setting").getString("voice_id"))
        assertEquals(voiceId, english.getJSONObject("voice_setting").getString("voice_id"))
        assertEquals(0.75, chinese.getJSONObject("voice_setting").getDouble("speed"), 0.001)
        assertEquals(1.5, english.getJSONObject("voice_setting").getDouble("speed"), 0.001)
        assertEquals("Chinese", chinese.getString("language_boost"))
        assertEquals("English", english.getString("language_boost"))
        assertTrue(chinese.getString("language_boost") != english.getString("language_boost"))
    }
}
