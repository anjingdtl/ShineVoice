package com.shinevoice.core.storage

import android.content.Context
import java.io.FileInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Properties

data class ZipVoiceModelLayout(
    val root: File,
    val modelDirectory: File,
    val encoder: File,
    val decoder: File,
    val vocoder: File,
    val tokens: File,
    val lexicon: File,
    val dataDirectory: File,
    val referenceAudio: File,
    val manifest: File,
) {
    fun missingFiles(): List<String> = buildList {
        if (!encoder.isFile) add(encoder.name)
        if (!decoder.isFile) add(decoder.name)
        if (!vocoder.isFile) add(vocoder.name)
        if (!tokens.isFile) add(tokens.name)
        if (!lexicon.isFile) add(lexicon.name)
        if (!dataDirectory.isDirectory) add("espeak-ng-data/")
        if (referenceAudio.isFile.not()) add("reference.wav")
        if (!manifest.isFile) add("model-manifest.properties")
    }

    fun isReady(): Boolean = missingFiles().isEmpty()

    fun checksumFiles(): List<File> = listOf(encoder, decoder, vocoder, tokens, lexicon)
}

data class ModelManifest(
    val modelId: String,
    val version: String,
    val engine: String,
    val sizeBytes: Long,
    val aggregateSha256: String,
)

data class ZipVoiceModelStatus(
    val ready: Boolean,
    val rootPath: String,
    val referencePath: String,
    val missingFiles: List<String>,
    val checksumVerified: Boolean = false,
    val manifest: ModelManifest? = null,
) {
    val summary: String
        get() = when {
            ready -> "模型与参考音频已就绪（SHA-256 已校验）"
            missingFiles.isNotEmpty() -> "缺少或不完整：${missingFiles.joinToString()}"
            else -> "模型完整性校验失败"
        }
}

/** Resolves deployable assets outside the APK so model binaries never enter Git. */
class ModelDirectoryResolver(context: Context) {
    companion object {
        const val ZIPVOICE_MODEL_ID = "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia"
        const val DEFAULT_REFERENCE_TEXT = "那还是三十六年前, 一九八七年. 我呢考上了武汉大学的计算机系."
    }

    private val externalRoot = context.getExternalFilesDir(null) ?: context.filesDir
    val zipVoiceRoot: File = File(externalRoot, "models/zipvoice")
    val modelDirectory: File = File(zipVoiceRoot, ZIPVOICE_MODEL_ID)
    val referenceAudio: File = File(externalRoot, "voices/default/reference.wav")
    val manifest: File = File(zipVoiceRoot, "model-manifest.properties")
    @Volatile private var cachedStatus: ZipVoiceModelStatus? = null

    fun layout(): ZipVoiceModelLayout = ZipVoiceModelLayout(
        root = zipVoiceRoot,
        modelDirectory = modelDirectory,
        encoder = File(modelDirectory, "encoder.int8.onnx"),
        decoder = File(modelDirectory, "decoder.int8.onnx"),
        vocoder = File(zipVoiceRoot, "vocos_24khz.onnx"),
        tokens = File(modelDirectory, "tokens.txt"),
        lexicon = File(modelDirectory, "lexicon.txt"),
        dataDirectory = File(modelDirectory, "espeak-ng-data"),
        referenceAudio = referenceAudio,
        manifest = manifest,
    )

    @Synchronized
    fun inspect(forceIntegrityCheck: Boolean = false): ZipVoiceModelStatus {
        if (!forceIntegrityCheck) cachedStatus?.let { return it }
        val layout = layout()
        val missing = layout.missingFiles()
        val parsedManifest = readManifest()
        val checksumVerified = missing.isEmpty() && parsedManifest != null && verifyChecksum(layout, parsedManifest)
        val status = ZipVoiceModelStatus(
            ready = missing.isEmpty() && checksumVerified,
            rootPath = layout.root.absolutePath,
            referencePath = layout.referenceAudio.absolutePath,
            missingFiles = missing,
            checksumVerified = checksumVerified,
            manifest = parsedManifest,
        )
        cachedStatus = status
        return status
    }

    private fun readManifest(): ModelManifest? {
        if (!manifest.isFile) return null
        return runCatching {
            val properties = Properties()
            FileInputStream(manifest).use { properties.load(it) }
            val modelId = properties.getProperty("modelId") ?: return@runCatching null
            val version = properties.getProperty("version") ?: return@runCatching null
            val engine = properties.getProperty("engine") ?: return@runCatching null
            val sizeBytes = properties.getProperty("sizeBytes")?.toLongOrNull() ?: return@runCatching null
            val aggregateSha256 = properties.getProperty("aggregateSha256") ?: return@runCatching null
            ModelManifest(modelId, version, engine, sizeBytes, aggregateSha256)
        }.getOrNull()
    }

    private fun verifyChecksum(layout: ZipVoiceModelLayout, manifest: ModelManifest): Boolean {
        if (manifest.modelId != ZIPVOICE_MODEL_ID || manifest.engine != "sherpa-onnx+zipvoice") return false
        val actualSize = layout.checksumFiles().sumOf { it.length() }
        if (actualSize != manifest.sizeBytes) return false
        val records = layout.checksumFiles().map { file ->
            "${file.name}:${sha256(file)}"
        }
        val aggregate = sha256(records.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
        return aggregate.equals(manifest.aggregateSha256, ignoreCase = true)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
