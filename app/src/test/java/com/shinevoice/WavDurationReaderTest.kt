package com.shinevoice

import com.shinevoice.core.audio.WavDurationReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Test

class WavDurationReaderTest {
    @Test
    fun acceptsListChunkAndUsesBytesActuallyPresentWhenDataSizeIsStale() {
        val output = ByteArrayOutputStream()
        output.write("RIFF".toByteArray(StandardCharsets.US_ASCII))
        writeLeInt(output, 0)
        output.write("WAVE".toByteArray(StandardCharsets.US_ASCII))
        output.write("fmt ".toByteArray(StandardCharsets.US_ASCII))
        writeLeInt(output, 16)
        writeLeShort(output, 1)
        writeLeShort(output, 1)
        writeLeInt(output, 24_000)
        writeLeInt(output, 48_000)
        writeLeShort(output, 2)
        writeLeShort(output, 16)
        output.write("LIST".toByteArray(StandardCharsets.US_ASCII))
        writeLeInt(output, 4)
        output.write("INFO".toByteArray(StandardCharsets.US_ASCII))
        val pcm = ByteArray(2_400)
        output.write("data".toByteArray(StandardCharsets.US_ASCII))
        writeLeInt(output, pcm.size * 2)
        output.write(pcm)
        val bytes = output.toByteArray()
        writeLeInt(bytes, 4, bytes.size - 8)

        val file = File.createTempFile("shinevoice-wav-", ".wav")
        try {
            file.writeBytes(bytes)
            assertEquals(50L, WavDurationReader.durationMs(file))
        } finally {
            file.delete()
        }
    }

    private fun writeLeShort(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun writeLeInt(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 24) and 0xff)
    }

    private fun writeLeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }
}
