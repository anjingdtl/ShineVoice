package com.shinevoice.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Normalized reference audio format used by the ZipVoice pipeline. */
const val TARGET_SAMPLE_RATE = 24_000

/** Mono 16-bit PCM audio. */
data class PcmAudio(
    val samples: ShortArray,
    val sampleRate: Int,
) {
    val durationMs: Long get() = samples.size.toLong() * 1000L / sampleRate
}

/**
 * RIFF/WAVE reader for PCM16 (fmt tag 1). Iterates chunks defensively:
 * odd-size chunk padding, multiple data chunks, truncation, and implausible
 * header fields are all rejected instead of misread. Multi-channel files are
 * downmixed by averaging ALL channels per frame (the old reader consumed only
 * two samples per frame but advanced by the full frame size, shifting every
 * subsequent byte for >2-channel files).
 */
object MonoWavReader {
    private const val RIFF = 0x46464952 // "RIFF"
    private const val WAVE = 0x45564157 // "WAVE"
    private const val CHUNK_FMT = 0x20746D66 // "fmt "
    private const val CHUNK_DATA = 0x61746164 // "data"
    private const val PCM = 1
    private const val MAX_CHANNELS = 8

    fun read(file: File): PcmAudio? = try {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 12) return null
            if (raf.readIntLe() != RIFF) return null
            raf.readIntLe() // chunk size (frequently wrong; byte bounds are authoritative)
            if (raf.readIntLe() != WAVE) return null
            var sampleRate = 0
            var channels = 0
            var bitsPerSample = 16
            var isPcm = true
            val dataChunks = mutableListOf<Pair<Long, Int>>()
            val fileLength = raf.length()
            while (raf.filePointer + 8 <= fileLength) {
                val chunkId = raf.readIntLe()
                val chunkSize = raf.readIntLe()
                if (chunkSize < 0) return null // corrupted/overflowing length
                val chunkStart = raf.filePointer
                // Never trust a chunk that claims to run past the file end;
                // clamp instead of skipping into garbage.
                val available = (fileLength - chunkStart).toInt()
                if (chunkSize > available) {
                    when (chunkId) {
                        CHUNK_DATA -> dataChunks += (chunkStart to available)
                        else -> Unit
                    }
                    break
                }
                when (chunkId) {
                    CHUNK_FMT -> {
                        if (chunkSize < 16) return null
                        val formatTag = raf.readShortLe()
                        isPcm = formatTag == PCM
                        channels = raf.readShortLe()
                        sampleRate = raf.readIntLe()
                        raf.readIntLe() // avg bytes/sec
                        raf.readShortLe() // block align
                        bitsPerSample = raf.readShortLe()
                        if (channels !in 1..MAX_CHANNELS) return null
                        if (sampleRate <= 0 || sampleRate > 384_000) return null
                        val remaining = chunkSize - 16
                        if (remaining > 0) raf.seek(
                            (chunkStart + chunkSize + (chunkSize and 1).toLong()).coerceAtMost(fileLength),
                        )
                    }
                    CHUNK_DATA -> {
                        dataChunks += (chunkStart to chunkSize)
                        raf.seek((chunkStart + chunkSize + (chunkSize and 1).toLong()).coerceAtMost(fileLength))
                    }
                    else -> {
                        raf.seek((chunkStart + chunkSize + (chunkSize and 1).toLong()).coerceAtMost(fileLength))
                    }
                }
            }
            if (!isPcm || sampleRate <= 0 || channels <= 0 || bitsPerSample != 16 || dataChunks.isEmpty()) {
                return null
            }
            val bytesPerSample = channels * 2
            var totalFrames = 0
            for ((_, size) in dataChunks) totalFrames += size / bytesPerSample
            if (totalFrames <= 0) return null
            val buffer = ByteArray(BUFFER_FRAMES * bytesPerSample)
            val mono = ShortArray(totalFrames)
            var offset = 0
            for ((start, size) in dataChunks) {
                raf.seek(start)
                var remaining = size - (size % bytesPerSample)
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining.toLong()).toInt()
                    raf.readFully(buffer, 0, toRead)
                    remaining -= toRead
                    val bb = ByteBuffer.wrap(buffer, 0, toRead).order(ByteOrder.LITTLE_ENDIAN)
                    val frameCount = toRead / bytesPerSample
                    if (channels == 1) {
                        repeat(frameCount) { frame -> mono[offset + frame] = bb.short }
                    } else {
                        // Average every channel of the frame; consume all
                        // channel samples so the stream stays frame-aligned.
                        repeat(frameCount) { frame ->
                            var acc = 0
                            repeat(channels) { acc += bb.short.toInt() }
                            mono[offset + frame] = (acc / channels).toShort()
                        }
                    }
                    offset += frameCount
                }
            }
            if (offset != totalFrames) return null
            PcmAudio(mono, sampleRate)
        }
    } catch (_: Exception) {
        null
    }

    private const val BUFFER_FRAMES = 4096

    /** Reads a little-endian 32-bit value from the current position. */
    private fun RandomAccessFile.readIntLe(): Int {
        val bytes = ByteArray(4)
        readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    /** Reads a little-endian 16-bit value from the current position. */
    private fun RandomAccessFile.readShortLe(): Int {
        val bytes = ByteArray(2)
        readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
    }
}

/**
 * Writer for mono 16-bit PCM WAV. Always writes to a fresh temporary file in
 * the target directory and atomically renames it into place, so a short clip
 * can never inherit tail bytes of a previously longer recording and a failed
 * write can never replace a valid file.
 */
object MonoWavWriter {
    fun write(file: File, samples: ShortArray, sampleRate: Int): Boolean = try {
        require(sampleRate > 0)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp-${System.nanoTime()}")
        val ok = runCatching {
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(0)
                val dataSize = samples.size * 2
                val bb = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
                bb.asShortBuffer().put(samples)
                raf.write(intLe(RIFF))
                raf.write(intLe(36 + dataSize))
                raf.write(intLe(WAVE))
                raf.write(intLe(CHUNK_FMT))
                raf.write(intLe(16))
                raf.write(shortLe(1))
                raf.write(shortLe(1))
                raf.write(intLe(sampleRate))
                raf.write(intLe(sampleRate * 2))
                raf.write(shortLe(2))
                raf.write(shortLe(16))
                raf.write(intLe(CHUNK_DATA))
                raf.write(intLe(dataSize))
                raf.write(bb.array())
            }
        }.isSuccess
        if (ok && tmp.length() == 44L + samples.size * 2L) {
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            true
        } else {
            tmp.delete()
            false
        }
    } catch (_: Exception) {
        false
    }

    private const val RIFF = 0x46464952
    private const val WAVE = 0x45564157
    private const val CHUNK_FMT = 0x20746D66
    private const val CHUNK_DATA = 0x61746164

    private fun intLe(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun shortLe(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
}

/**
 * Resampler with a windowed-sinc anti-aliasing filter for downsampling.
 *
 * The old linear-interpolation kernel mirrored >Nyquist content of a 44.1/48
 * kHz recording into the audible band (hiss-like artifacts in reference
 * audio). This implementation low-pass filters at ~0.9 * target Nyquist with a
 * Blackman-windowed sinc kernel before interpolating, and falls back to the
 * cheap linear path for upsampling (no aliasing possible above the source's
 * own Nyquist).
 */
object Resampler {
    private const val KERNEL_HALF_WIDTH = 24
    private const val CUTOFF_MARGIN = 0.9

    /** Resamples mono samples to [targetRate]; returns the input if rates match. */
    fun toRate(targetRate: Int, samples: ShortArray, sourceRate: Int): ShortArray {
        if (sourceRate == targetRate || samples.isEmpty()) return samples
        require(targetRate > 0 && sourceRate > 0)
        val ratio = targetRate.toDouble() / sourceRate.toDouble()
        return if (ratio >= 1.0) {
            upsampleLinear(samples, ratio)
        } else {
            downsampleWithAntiAliasing(samples, ratio)
        }
    }

    private fun upsampleLinear(samples: ShortArray, ratio: Double): ShortArray {
        val outSize = (samples.size * ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(outSize)
        for (i in 0 until outSize) {
            val position = i / ratio
            val index = position.toInt()
            val frac = position - index
            val a = samples[index.coerceIn(0, samples.size - 1)].toInt()
            val b = samples[(index + 1).coerceIn(0, samples.size - 1)].toInt()
            out[i] = (a + ((b - a) * frac).toInt()).toShort()
        }
        return out
    }

    /**
     * Windowed-sinc interpolation: each output sample is a sinc-weighted sum
     * over a local window of input samples, with the sinc cutoff scaled to the
     * lower of the two Nyquist frequencies. This both band-limits (kills
     * aliasing) and interpolates in one pass.
     */
    private fun downsampleWithAntiAliasing(samples: ShortArray, ratio: Double): ShortArray {
        val outSize = (samples.size * ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(outSize)
        // cutoff as a fraction of the SOURCE sample rate: the sinc's first zero
        // sits at cutoffInSourceHz. Scaling by ratio maps the target Nyquist
        // into source-frequency space.
        val cutoff = 0.5 * ratio * CUTOFF_MARGIN // cycles per source sample
        val windowedSinc = DoubleArray(2 * KERNEL_HALF_WIDTH + 1)
        var sum = 0.0
        for (j in windowedSinc.indices) {
            val x = (j - KERNEL_HALF_WIDTH).toDouble()
            val sinc = if (x == 0.0) 2 * PI * cutoff else sin(2 * PI * cutoff * x) / x
            val window = blackman(j.toDouble() / (windowedSinc.size - 1))
            windowedSinc[j] = sinc * window
            sum += windowedSinc[j]
        }
        for (j in windowedSinc.indices) windowedSinc[j] /= sum // unity DC gain

        for (i in 0 until outSize) {
            val center = i / ratio
            val centerIndex = center.toInt()
            val frac = center - centerIndex
            var acc = 0.0
            for (k in -KERNEL_HALF_WIDTH..KERNEL_HALF_WIDTH) {
                val idx = centerIndex + k
                if (idx < 0 || idx >= samples.size) continue
                // Kernel evaluated at the fractional offset (k - frac).
                val weight = interpolateKernel(windowedSinc, k - frac + KERNEL_HALF_WIDTH)
                acc += samples[idx] * weight
            }
            out[i] = acc.roundToShort()
        }
        return out
    }

    private fun interpolateKernel(kernel: DoubleArray, pos: Double): Double {
        if (pos <= 0.0) return kernel[0]
        if (pos >= kernel.lastIndex) return kernel[kernel.lastIndex]
        val lo = pos.toInt()
        val f = pos - lo
        return kernel[lo] * (1 - f) + kernel[lo + 1] * f
    }

    private fun Double.roundToShort(): Short {
        val v = kotlin.math.round(this)
        return v.coerceIn(-32768.0, 32767.0).toInt().toShort()
    }

    private fun blackman(t: Double): Double =
        0.42 - 0.5 * kotlin.math.cos(2 * PI * t) + 0.08 * kotlin.math.cos(4 * PI * t)
}
