package com.shinevoice

import com.shinevoice.core.storage.AudioExporter
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** ZIP export contract: entries are playable WAV files with readable names. */
class AudioExporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeWav(dir: File, name: String, bytes: Int): File {
        val wav = File(dir, name)
        // Minimal RIFF/WAVE header + payload (16-bit mono PCM, arbitrary rate).
        val header = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0, 0, 0, 0,
            'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
            'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(),
        )
        wav.writeBytes(header + ByteArray(bytes))
        return wav
    }

    @Test
    fun exportProducesExtractableZipWithWavEntries() {
        val wav1 = writeWav(tmp.root, "a.wav", 1024)
        val wav2 = writeWav(tmp.root, "b.wav", 2048)
        val destination = File(tmp.root, "exports/out.zip")
        val result = AudioExporter.exportAsZip(
            listOf("你好世界" to wav1, "second clip" to wav2),
            destination,
        )
        assertTrue("export failed: ${result.exceptionOrNull()}", result.isSuccess)
        val zip = result.getOrThrow()
        assertTrue(zip.length() > 0)
        ZipFile(zip).use { archive ->
            val entries = archive.entries().toList()
            assertEquals(2, entries.size)
            entries.forEach { entry ->
                assertTrue("entry should be .wav: ${entry.name}", entry.name.endsWith(".wav"))
                archive.getInputStream(entry).use { stream ->
                    val header = ByteArray(4)
                    assertEquals(4, stream.read(header))
                    assertEquals("RIFF", String(header, Charsets.US_ASCII))
                }
            }
            // The sanitized Chinese title survives in the entry name.
            assertTrue(entries.any { it.name.contains("你好世界") })
        }
    }

    @Test
    fun exportFailsWhenSourceMissing() {
        val result = AudioExporter.exportAsZip(
            listOf("missing" to File(tmp.root, "nope.wav")),
            File(tmp.root, "exports/none.zip"),
        )
        assertTrue(result.isFailure)
    }
}
