package com.shinevoice.core.audio

import android.content.Context
import android.net.Uri
import java.io.File

/** Imports a user-picked audio file and normalizes it into a profile's reference.wav. */
class ReferenceAudioImporter(private val context: Context) {

    /**
     * Copies the picked file into [profileDir]/source.<ext>, decodes it to mono
     * 16-bit PCM at TARGET_SAMPLE_RATE, and writes [profileDir]/reference.wav.
     * Returns the final reference WAV on success.
     */
    fun import(uri: Uri, profileDir: File): Result<File> = runCatching {
        profileDir.mkdirs()
        val displayName = (context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }) ?: "imported.audio"
        val extension = displayName.substringAfterLast('.', "audio")
            .lowercase()
            .ifBlank { "audio" }
        val source = File(profileDir, "source.$extension")
        context.contentResolver.openInputStream(uri)?.use { input ->
            source.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("无法读取所选音频文件")

        val decoded = AudioCodecDecoder.decodeToPcm(source)
            ?: throw IllegalArgumentException("不支持的音频格式，或文件已损坏（支持 WAV/MP3/M4A/AAC）")
        val reference = File(profileDir, "reference.wav")
        check(MonoWavWriter.write(reference, decoded.samples, decoded.sampleRate)) {
            "参考音频写入失败"
        }
        reference
    }
}