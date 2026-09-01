package com.shinevoice

import com.shinevoice.core.audio.MonoWavReader
import com.shinevoice.core.audio.MonoWavWriter
import com.shinevoice.core.audio.PcmAudio
import com.shinevoice.core.audio.Resampler
import com.shinevoice.core.audio.TARGET_SAMPLE_RATE
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmAudioTest {
    @Test
    fun wavRoundTripPreservesSamplesAndRate() {
        val original = ShortArray(4_800) { ((it * 37) % 65536 - 32768).toShort() }
        val file = File.createTempFile("shinevoice-test", ".wav")
        try {
            assertTrue(MonoWavWriter.write(file, original, TARGET_SAMPLE_RATE))
            val decoded = MonoWavReader.read(file)
            assertTrue("WAV should decode", decoded != null)
            assertEquals(TARGET_SAMPLE_RATE, decoded!!.sampleRate)
            assertEquals(original.size, decoded.samples.size)
            assertTrue(original.zip(decoded.samples).all { (a, b) -> a == b })
        } finally {
            file.delete()
        }
    }

    @Test
    fun resamplerKeepsDurationProportionally() {
        val source = ShortArray(44_100) { (it % 32767).toShort() }
        val result = Resampler.toRate(TARGET_SAMPLE_RATE, source, 44_100)
        val expected = (44_100.0 * TARGET_SAMPLE_RATE / 44_100.0).toInt()
        assertEquals(expected, result.size)
        // First sample must survive exactly.
        assertEquals(source[0], result[0])
    }

    @Test
    fun resamplerSameRateIsIdentity() {
        val source = ShortArray(100) { it.toShort() }
        assertTrue(Resampler.toRate(24_000, source, 24_000) === source)
    }

    @Test
    fun pcmAudioDurationMs() {
        val audio = PcmAudio(ShortArray(TARGET_SAMPLE_RATE), TARGET_SAMPLE_RATE)
        assertEquals(1_000L, audio.durationMs)
    }
}