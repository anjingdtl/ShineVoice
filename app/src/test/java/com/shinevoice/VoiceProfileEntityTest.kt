package com.shinevoice

import com.shinevoice.data.db.VoiceProfileEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Binding-badge semantics for the voice profile entity. */
class VoiceProfileEntityTest {
    private fun profile(
        engine: String? = null,
        voice: String? = null,
        reference: String? = null,
        source: String? = null,
        cloud: String? = null,
    ) = VoiceProfileEntity(
        id = "p1",
        displayName = "测试音色",
        referenceAudioPath = reference,
        referenceText = null,
        sourceAudioPath = source,
        minimaxVoiceId = cloud,
        androidTtsEngine = engine,
        androidTtsVoice = voice,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun systemBindingCountsWhenEitherEngineOrVoiceIsBound() {
        assertTrue(profile(engine = "com.google.android.tts", voice = null).hasSystemBinding)
        assertTrue(profile(engine = null, voice = "cmn-cn-x-ccc-local").hasSystemBinding)
        assertTrue(
            profile(engine = "com.google.android.tts", voice = "cmn-cn-x-ccc-local").hasSystemBinding,
        )
    }

    @Test
    fun systemBindingIsAbsentOnlyWhenBothFieldsAreEmpty() {
        assertFalse(profile().hasSystemBinding)
        assertFalse(profile(engine = "", voice = "  ").hasSystemBinding)
    }

    @Test
    fun localBindingFollowsReferenceOrSourceAudio() {
        assertTrue(profile(reference = "/voices/p1/reference.wav").hasLocalBinding)
        assertTrue(profile(source = "/voices/p1/source.m4a").hasLocalBinding)
        assertFalse(profile().hasLocalBinding)
    }

    @Test
    fun cloudBindingRequiresNonBlankVoiceId() {
        assertTrue(profile(cloud = "voice-123").hasCloudBinding)
        assertFalse(profile(cloud = null).hasCloudBinding)
        assertFalse(profile(cloud = "").hasCloudBinding)
    }
}
