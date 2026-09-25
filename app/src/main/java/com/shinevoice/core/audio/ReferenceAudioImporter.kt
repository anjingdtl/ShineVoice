package com.shinevoice.core.audio

import android.content.Context
import android.net.Uri
import java.io.File

/** Imports a user-picked audio file and normalizes it into a profile's reference.wav. */
class ReferenceAudioImporter(private val context: Context) {

    /**
     * Copies the picked file into [profileDir]/source.<ext> (kept for future
     * re-normalization), decodes it, and runs the SAME normalization pipeline
     * as in-app recording ([AudioNormalizer]): mono 16-bit PCM at
     * TARGET_SAMPLE_RATE, anti-aliased resampling, atomic replacement of
     * reference.wav. Returns the reference WAV and its quality report.
     */
    fun import(uri: Uri, profileDir: File): Result<AudioNormalizer.Output> = runCatching {
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
        // Copy through a temp file so a previously imported source is never
        // left half-overwritten.
        val sourceTmp = File(profileDir, "source.$extension.incoming")
        context.contentResolver.openInputStream(uri)?.use { input ->
            sourceTmp.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("无法读取所选音频文件")
        if (!sourceTmp.renameTo(source)) {
            sourceTmp.copyTo(source, overwrite = true)
            sourceTmp.delete()
        }

        val decoded = AudioCodecDecoder.decodeToPcm(source)
            ?: throw IllegalArgumentException("不支持的音频格式，或文件已损坏（支持 WAV/MP3/M4A/AAC）")
        val reference = File(profileDir, "reference.wav")
        AudioNormalizer.normalize(decoded, reference).getOrThrow()
    }
}
