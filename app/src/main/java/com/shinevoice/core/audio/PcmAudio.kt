package com.shinevoice.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Normalized reference audio format used by the ZipVoice pipeline. */
const val TARGET_SAMPLE_RATE = 24_000

/** Mono 16-bit PCM audio. */
data class PcmAudio(
    val samples: ShortArray,
    val sampleRate: Int,
) {
    val durationMs: Long get() = samples.size.toLong() * 1000L / sampleRate
}

/** Minimal RIFF/WAVE reader for PCM16 (fmt tag 1). Downmixes stereo to mono. */
object MonoWavReader {
    private const val RIFF = 0x46464952 // "RIFF"
    private const val WAVE = 0x45564157 // "WAVE"
    private const val CHUNK_FMT = 0x20746D66 // "fmt "
    private const val CHUNK_DATA = 0x61746164 // "data"
    private const val PCM = 1

    fun read(file: File): PcmAudio? = try {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.readInt() != RIFF) return null
            raf.readInt() // chunk size
            if (raf.readInt() != WAVE) return null
            var sampleRate = 0
            var channels = 0
            var bitsPerSample = 16
            var isPcm = true
            val dataChunks = mutableListOf<Pair<Long, Int>>()
            while (raf.filePointer + 8 <= raf.length()) {
                val chunkId = raf.readInt()
                val chunkSize = raf.readInt()
                when (chunkId) {
                    CHUNK_FMT -> {
                        val formatTag = raf.readShort().toInt() and 0xffff
                        isPcm = formatTag == PCM
                        channels = raf.readShort().toInt() and 0xffff
                        sampleRate = raf.readInt()
                        raf.readInt() // avg bytes/sec
                        raf.readShort() // block align
                        bitsPerSample = raf.readShort().toInt() and 0xffff
                        val remaining = chunkSize - 16
                        if (remaining > 0) raf.skipBytes(remaining)
                    }
                    CHUNK_DATA -> {
                        dataChunks += (raf.filePointer to chunkSize)
                        raf.skipBytes(chunkSize)
                    }
                    else -> {
                        val skip = chunkSize + (chunkSize and 1)
                        if (skip < 0 || raf.filePointer + skip > raf.length() + 8) return null
                        raf.skipBytes(skip)
                    }
                }
            }
            if (!isPcm || sampleRate <= 0 || channels <= 0 || bitsPerSample != 16 || dataChunks.isEmpty()) {
                return null
            }
            val bytesPerSample = channels * 2
            val totalFrames = dataChunks.sumOf { it.second / bytesPerSample }
            if (totalFrames <= 0) return null
            val buffer = ByteArray(BUFFER_FRAMES * bytesPerSample)
            val mono = ShortArray(totalFrames)
            var offset = 0
            for ((start, size) in dataChunks) {
                raf.seek(start)
                var remaining = size
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining.toLong()).toInt()
                    raf.readFully(buffer, 0, toRead)
                    remaining -= toRead
                    val bb = ByteBuffer.wrap(buffer, 0, toRead).order(ByteOrder.LITTLE_ENDIAN)
                    val frameCount = toRead / bytesPerSample
                    repeat(frameCount) { frame ->
                        val left = bb.short.toInt()
                        val right = if (channels > 1) bb.short.toInt() else left
                        mono[offset + frame] = ((left + right) / 2).toShort()
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
}

/** Writer for mono 16-bit PCM WAV. */
object MonoWavWriter {
    fun write(file: File, samples: ShortArray, sampleRate: Int): Boolean = try {
        RandomAccessFile(file, "rw").use { raf ->
            // Placeholder header, fixed up after data.
            raf.write(ByteArray(44))
            val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bb.asShortBuffer().put(samples)
            raf.write(bb.array())
            val dataSize = samples.size * 2
            val totalSize = 36 + dataSize
            raf.seek(0)
            raf.write(intLe(RIFF))
            raf.write(intLe(totalSize))
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
        }
        true
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

/** Linear-interpolation resampler (good enough for reference audio preprocessing). */
object Resampler {
    /** Downmixes/resamples mono samples to [targetRate]; returns the input if rates match. */
    fun toRate(targetRate: Int, samples: ShortArray, sourceRate: Int): ShortArray {
        if (sourceRate == targetRate || samples.isEmpty()) return samples
        val ratio = targetRate.toDouble() / sourceRate.toDouble()
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
}