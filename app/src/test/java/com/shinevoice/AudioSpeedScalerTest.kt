package com.shinevoice

import com.shinevoice.core.audio.AudioSpeedScaler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioSpeedScalerTest {
    @Test
    fun oneXReturnsTheOriginalSamples() {
        val samples = floatArrayOf(0f, 0.5f, 1f)

        assertEquals(samples.toList(), AudioSpeedScaler.scale(samples, 1.0f).toList())
    }

    @Test
    fun twoXShortensAndHalfXLengthensTheWaveform() {
        val samples = FloatArray(100)

        assertEquals(50, AudioSpeedScaler.scale(samples, 2.0f).size)
        assertEquals(200, AudioSpeedScaler.scale(samples, 0.5f).size)
    }

    @Test
    fun rejectsNonPositiveOrNonFiniteSpeed() {
        val samples = floatArrayOf(0f, 1f)

        assertThrows(IllegalArgumentException::class.java) { AudioSpeedScaler.scale(samples, 0f) }
        assertThrows(IllegalArgumentException::class.java) { AudioSpeedScaler.scale(samples, Float.NaN) }
    }
}
