package com.shinevoice.core.storage

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Packages generated WAVs into a single ZIP for batch export. */
object AudioExporter {
    fun exportAsZip(files: List<Pair<String, File>>, destination: File): Result<File> = runCatching {
        destination.parentFile?.mkdirs()
        FileOutputStream(destination).use { fos ->
            ZipOutputStream(fos).use { zip ->
                files.forEachIndexed { index, (name, file) ->
                    require(file.isFile) { "文件不存在：${file.absolutePath}" }
                    val entryName = "${index + 1}_${sanitize(name)}.wav"
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        destination
    }

    fun sanitize(name: String): String =
        name.replace(Regex("[^\\w\\u4e00-\\u9fa5.-]"), "_").ifBlank { "audio" }
}