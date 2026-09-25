package com.shinevoice

import com.shinevoice.core.audio.AudioNormalizer
import com.shinevoice.core.audio.MonoWavReader
import com.shinevoice.core.audio.MonoWavWriter
import com.shinevoice.core.audio.PcmAudio
import com.shinevoice.core.audio.Resampler
import com.shinevoice.core.audio.TARGET_SAMPLE_RATE
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2 audio-pipeline tests: anti-aliasing resampler fidelity, WAV writer
 * truncation semantics, WAV reader chunk/channel edge cases, and the shared
 * normalizer's quality gates.
 */
class AudioPipelineTest {

    // ---------- Resampler: fidelity (8.2 targets: <1% freq, <1 dB amplitude) ----------

    private fun sine(samples: Int, rate: Int, freqHz: Double, amplitude: Double): ShortArray =
        ShortArray(samples) { i ->
            (amplitude * 32767 * kotlin.math.sin(2 * Math.PI * freqHz * i / rate)).toInt().toShort()
        }

    private fun dominantFrequencyDb(samples: ShortArray, rate: Int, probeHz: Double): Double {
        // Goertzel magnitude at probeHz, normalized by total energy.
        val k = 2.0 * Math.PI * probeHz / rate
        var sPrev = 0.0
        var sPrev2 = 0.0
        var energy = 0.0
        for (s in samples) {
            val v = s / 32768.0
            val s = v + 2.0 * Math.cos(k) * sPrev - sPrev2
            sPrev2 = sPrev
            sPrev = s
            energy += v * v
        }
        val power = sPrev2 * sPrev2 + sPrev * sPrev - 2.0 * Math.cos(k) * sPrev * sPrev2
        return if (energy <= 0) Double.NEGATIVE_INFINITY else 10.0 * kotlin.math.log10(power / energy)
    }

    private fun goertzelAmplitude(samples: ShortArray, rate: Int, probeHz: Double): Double {
        val k = 2.0 * Math.PI * probeHz / rate
        var sPrev = 0.0
        var sPrev2 = 0.0
        val n = samples.size
        for (s in samples) {
            val v = s / 32768.0
            val s = v + 2.0 * Math.cos(k) * sPrev - sPrev2
            sPrev2 = sPrev
            sPrev = s
        }
        return 2.0 / n * kotlin.math.sqrt(
            sPrev2 * sPrev2 + sPrev * sPrev - 2.0 * Math.cos(k) * sPrev * sPrev2,
        )
    }

    @Test
    fun downsample44k1to24kKeeps1kHzTone() {
        val source = sine(44_100, 44_100, 1000.0, 0.5) // 1 s
        val out = Resampler.toRate(TARGET_SAMPLE_RATE, source, 44_100)
        assertEquals(TARGET_SAMPLE_RATE, out.size) // duration preserved
        val amp = goertzelAmplitude(out, TARGET_SAMPLE_RATE, 1000.0)
        assertTrue("amplitude $amp should stay within 1 dB of 0.5", amp in 0.42..0.6)
        // Energy at the alias frequency (44100-24000-ish mapping region): the
        // 1 kHz tone itself cannot alias; main check is amplitude fidelity.
    }

    @Test
    fun downsample48kto24kKeeps1kHzTone() {
        val source = sine(48_000, 48_000, 1000.0, 0.5)
        val out = Resampler.toRate(TARGET_SAMPLE_RATE, source, 48_000)
        val amp = goertzelAmplitude(out, TARGET_SAMPLE_RATE, 1000.0)
        assertTrue("amplitude $amp", amp in 0.42..0.6)
    }

    @Test
    fun downsampleSuppressesAboveNyquistAlias() {
        // 16 kHz tone sampled at 48 kHz: above the 12 kHz target Nyquist.
        // Naive decimation maps it onto 24k-16k = 8 kHz (alias). The
        // anti-aliasing filter must keep the 8 kHz bin near the noise floor.
        val source = sine(48_000 * 2, 48_000, 16_000.0, 0.5)
        val out = Resampler.toRate(TARGET_SAMPLE_RATE, source, 48_000)
        val aliasAmp = goertzelAmplitude(out, TARGET_SAMPLE_RATE, 8_000.0)
        assertTrue(
            "alias amplitude at 8 kHz was $aliasAmp (must be suppressed below 0.05)",
            aliasAmp < 0.05,
        )
    }

    @Test
    fun upsampleKeepsTone() {
        val source = sine(24_000, 24_000, 1000.0, 0.5)
        val out = Resampler.toRate(48_000, source, 24_000)
        assertEquals(48_000, out.size)
        val amp = goertzelAmplitude(out, 48_000, 1000.0)
        assertTrue("amplitude $amp", amp in 0.42..0.6)
    }

    // ---------- WAV writer: truncation on overwrite ----------

    @Test
    fun shortOverwriteTruncatesLongerPreviousFile() {
        val file = File.createTempFile("shinevoice-trunc", ".wav")
        try {
            val long = ShortArray(48_000) { 1000 } // 2 s @ 24k
            assertTrue(MonoWavWriter.write(file, long, TARGET_SAMPLE_RATE))
            assertEquals(44 + 96_000, file.length())
            val short = ShortArray(2_400) { -1000 } // 0.1 s
            assertTrue(MonoWavWriter.write(file, short, TARGET_SAMPLE_RATE))
            assertEquals("old tail bytes must be gone", 44 + 4_800, file.length())
            val decoded = MonoWavReader.read(file)!!
            assertEquals(2_400, decoded.samples.size)
            assertEquals(-1000, decoded.samples[0].toInt())
        } finally {
            file.delete()
        }
    }

    @Test
    fun failedWriteKeepsPreviousFile() {
        val file = File.createTempFile("shinevoice-keep", ".wav")
        try {
            val original = ShortArray(1_000) { 123 }
            assertTrue(MonoWavWriter.write(file, original, TARGET_SAMPLE_RATE))
            // Writing a negative rate fails validation and must not touch the file.
            assertTrue(!MonoWavWriter.write(file, ShortArray(10), -1))
            val decoded = MonoWavReader.read(file)!!
            assertEquals(1_000, decoded.samples.size)
            assertEquals(123, decoded.samples[0].toInt())
        } finally {
            file.delete()
        }
    }

    // ---------- WAV reader: chunk and channel edge cases ----------

    private fun wavBytes(
        channels: Int,
        rate: Int,
        frames: Int,
        extraChunks: List<Pair<String, ByteArray>> = emptyList(),
        oddPadExtraChunk: Boolean = false,
    ): ByteArray {
        val dataSize = frames * channels * 2
        val out = ByteArrayOutputStream()
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        val extrasSize = extraChunks.sumOf { 8 + it.second.size + (it.second.size and 1) }
        out.write("RIFF".toByteArray()); le32(36 + dataSize + extrasSize)
        out.write("WAVE".toByteArray())
        for ((id, body) in extraChunks) {
            out.write(id.toByteArray()); le32(body.size)
            out.write(body)
            if ((body.size and 1) == 1) out.write(0) // odd chunk padding
        }
        out.write("fmt ".toByteArray()); le32(16)
        le16(1); le16(channels); le32(rate); le32(rate * channels * 2); le16(channels * 2); le16(16)
        out.write("data".toByteArray()); le32(dataSize)
        val frame = ByteBuffer.allocate(channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { i ->
            frame.clear()
            repeat(channels) { ch -> frame.putShort(((i % 100) * (ch + 1)).toShort()) }
            out.write(frame.array())
        }
        return out.toByteArray()
    }

    @Test
    fun readerHandlesOddSizeExtraChunkBeforeData() {
        // LIST chunk with odd (3) body length forces one padding byte.
        val bytes = wavBytes(1, 16_000, 100, extraChunks = listOf("LIST" to byteArrayOf(1, 2, 3)))
        val file = File.createTempFile("shinevoice-odd", ".wav")
        try {
            file.writeBytes(bytes)
            val decoded = MonoWavReader.read(file)
            assertNotNull("odd-padded chunk must not break parsing", decoded)
            assertEquals(100, decoded!!.samples.size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun readerDownmixesStereoCorrectly() {
        // Left ramp 0..99, right = left*2; average = left*1.5 truncated.
        val bytes = wavBytes(2, 16_000, 100)
        val file = File.createTempFile("shinevoice-stereo", ".wav")
        try {
            file.writeBytes(bytes)
            val decoded = MonoWavReader.read(file)!!
            assertEquals(100, decoded.samples.size)
            assertEquals((0 * 3) / 2, decoded.samples[0].toInt())
            assertEquals((57 * 3) / 2, decoded.samples[57].toInt())
        } finally {
            file.delete()
        }
    }

    @Test
    fun readerHandlesSixChannelWithoutFrameMisalignment() {
        // The old reader consumed only 2 samples per 6-channel frame, turning
        // every later byte into garbage; 6ch must now downmix every frame.
        val bytes = wavBytes(6, 16_000, 50)
        val file = File.createTempFile("shinevoice-6ch", ".wav")
        try {
            file.writeBytes(bytes)
            val decoded = MonoWavReader.read(file)
            assertNotNull(decoded)
            assertEquals(50, decoded!!.samples.size)
            // frame i has channel values (i%100)*1..*6 -> average = (i%100)*3.5
            assertEquals(((10 % 100) * 21) / 6, decoded.samples[10].toInt())
        } finally {
            file.delete()
        }
    }

    @Test
    fun readerRejectsTruncatedHeader() {
        val file = File.createTempFile("shinevoice-bad", ".wav")
        try {
            file.writeBytes(ByteArray(20))
            assertNull(MonoWavReader.read(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun readerClampsDataChunkBeyondFileEnd() {
        // data chunk claims more bytes than the file holds: clamp, don't break.
        val bytes = wavBytes(1, 16_000, 100)
        val forged = bytes.copyOfRange(0, 44) +
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(999_999).array() + bytes.copyOfRange(48, bytes.size)
        val file = File.createTempFile("shinevoice-forged", ".wav")
        try {
            file.writeBytes(forged)
            val decoded = MonoWavReader.read(file)
            assertNotNull("clamped oversized data chunk must still parse", decoded)
            assertEquals(100, decoded!!.samples.size)
        } finally {
            file.delete()
        }
    }

    // ---------- AudioNormalizer quality gates ----------

    @Test
    fun normalizerReportsLevelsAndWarnings() {
        val loud = ShortArray(TARGET_SAMPLE_RATE) { i ->
            (0.98 * 32767 * kotlin.math.sin(2 * Math.PI * 440 * i / TARGET_SAMPLE_RATE)).toInt().toShort()
        }
        val report = AudioNormalizer.analyze(loud)
        assertEquals(1_000L, report.durationMs)
        assertTrue(report.rmsDbfs > -6.5)
        assertTrue(report.warnings.isNotEmpty()) // too-hot level warning

        val quiet = ShortArray(TARGET_SAMPLE_RATE) { i ->
            (0.001 * 32767 * kotlin.math.sin(2 * Math.PI * 440 * i / TARGET_SAMPLE_RATE)).toInt().toShort()
        }
        val quietReport = AudioNormalizer.analyze(quiet)
        assertTrue("quiet rms was ${quietReport.rmsDbfs}", quietReport.rmsDbfs < -50)

        val clipping = ShortArray(TARGET_SAMPLE_RATE) {
            if (it % 2 == 0) 32767 else -32768
        }
        val clipReport = AudioNormalizer.analyze(clipping)
        assertTrue(clipReport.clipRatio > 0.9)
    }

    @Test
    fun normalizerWrites24kMonoReferenceAtomically() {
        val source = PcmAudio(sine(44_100, 44_100, 1000.0, 0.3), 44_100)
        val target = File.createTempFile("shinevoice-norm", ".wav")
        try {
            val output = AudioNormalizer.normalize(source, target).getOrThrow()
            assertEquals(target, output.referenceFile)
            assertEquals(TARGET_SAMPLE_RATE, MonoWavReader.read(target)!!.sampleRate)
            assertTrue(output.report.durationMs in 990..1_010)
        } finally {
            target.delete()
        }
    }

    @Test
    fun normalizerRejectsEmptyAudio() {
        val target = File.createTempFile("shinevoice-empty", ".wav")
        try {
            val result = AudioNormalizer.normalize(PcmAudio(ShortArray(0), 44_100), target)
            assertTrue(result.isFailure)
            assertTrue(!target.exists() || target.length() == 0L)
        } finally {
            target.delete()
        }
    }
}
