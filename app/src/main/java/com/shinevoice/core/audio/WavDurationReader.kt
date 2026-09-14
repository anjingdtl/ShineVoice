package com.shinevoice.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/** Reads duration from PCM WAV files without trusting a stale data-chunk size. */
object WavDurationReader {
    fun durationMs(file: File): Long? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 44L) return@runCatching null
            val riff = ByteArray(12)
            raf.readFully(riff)
            if (String(riff, 0, 4, StandardCharsets.US_ASCII) != "RIFF" ||
                String(riff, 8, 4, StandardCharsets.US_ASCII) != "WAVE"
            ) {
                return@runCatching null
            }

            var sampleRate = 0L
            var channels = 0
            var bytesPerSample = 0
            var dataBytes = 0L
            var dataOffset = -1L
            while (raf.filePointer + 8L <= raf.length()) {
                val chunkHeader = ByteArray(8)
                raf.readFully(chunkHeader)
                val chunkId = String(chunkHeader, 0, 4, StandardCharsets.US_ASCII)
                val chunkSize = littleEndianUInt32(chunkHeader, 4)
                when (chunkId) {
                    "fmt " -> {
                        if (chunkSize < 16L) return@runCatching null
                        val fmt = ByteArray(16)
                        raf.readFully(fmt)
                        channels = littleEndianUInt16(fmt, 2)
                        sampleRate = littleEndianUInt32(fmt, 4)
                        bytesPerSample = littleEndianUInt16(fmt, 14) / 8
                        val remaining = chunkSize - 16L
                        if (remaining > 0L) {
                            raf.seek((raf.filePointer + remaining + (remaining and 1L))
                                .coerceAtMost(raf.length()))
                        }
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataBytes = chunkSize
                        break
                    }
                    else -> {
                        raf.seek((raf.filePointer + chunkSize + (chunkSize and 1L))
                            .coerceAtMost(raf.length()))
                    }
                }
            }

            val availableDataBytes = if (dataOffset >= 0L) {
                (raf.length() - dataOffset).coerceAtLeast(0L)
            } else {
                0L
            }
            val effectiveDataBytes = when {
                dataBytes <= 0L -> availableDataBytes
                availableDataBytes in 1 until dataBytes -> availableDataBytes
                else -> dataBytes
            }
            if (sampleRate <= 0L || channels <= 0 || bytesPerSample <= 0 || effectiveDataBytes <= 0L) {
                return@runCatching null
            }
            effectiveDataBytes * 1000L / (sampleRate * channels * bytesPerSample)
        }
    }.getOrNull()

    private fun littleEndianUInt16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianUInt32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)
}
