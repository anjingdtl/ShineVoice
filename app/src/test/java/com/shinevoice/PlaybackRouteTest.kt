package com.shinevoice

import com.shinevoice.core.audio.PlaybackRoute
import org.junit.Assert.assertEquals
import org.junit.Test

/** Persistence parsing rules for the speaker/earpiece playback route setting. */
class PlaybackRouteTest {
    @Test
    fun fromNameParsesStoredNames() {
        assertEquals(PlaybackRoute.SPEAKER, PlaybackRoute.fromName("speaker"))
        assertEquals(PlaybackRoute.EARPIECE, PlaybackRoute.fromName("earpiece"))
    }

    @Test
    fun fromNameFallsBackToSpeakerForUnknownOrNullValues() {
        assertEquals(PlaybackRoute.SPEAKER, PlaybackRoute.fromName(null))
        assertEquals(PlaybackRoute.SPEAKER, PlaybackRoute.fromName(""))
        assertEquals(PlaybackRoute.SPEAKER, PlaybackRoute.fromName("headset"))
        assertEquals(PlaybackRoute.SPEAKER, PlaybackRoute.fromName("EARPIECE"))
    }
}
