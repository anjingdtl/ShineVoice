package com.shinevoice.core.storage

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

class WavStorage(private val context: Context) {
    private val generatedDirectory = File(context.filesDir, "generated")

    init {
        generatedDirectory.mkdirs()
    }

    fun generatedFile(taskId: String): File = File(generatedDirectory, "$taskId.wav")

    fun copyToUri(source: File, destination: Uri): Result<Unit> = runCatching {
        require(source.isFile) { "Generated WAV does not exist: ${source.absolutePath}" }
        context.contentResolver.openOutputStream(destination)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IOException("Unable to open output URI")
    }
}

