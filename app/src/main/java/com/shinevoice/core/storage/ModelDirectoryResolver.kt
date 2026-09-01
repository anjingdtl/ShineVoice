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
    /** Model-only readiness: the default reference.wav is a VoiceProfile concern, not a model concern. */
    fun missingFiles(): List<String> = buildList {
        if (!encoder.isFile) add(encoder.name)
        if (!decoder.isFile) add(decoder.name)
        if (!vocoder.isFile) add(vocoder.name)
        if (!tokens.isFile) add(tokens.name)
        if (!lexicon.isFile) add(lexicon.name)
        if (!dataDirectory.isDirectory) add("espeak-ng-data/")
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
    val missingFiles: List<String>,
    val checksumVerified: Boolean = false,
    val manifest: ModelManifest? = null,
) {
    val summary: String
        get() = when {
            ready -> "模型已就绪（SHA-256 已校验）"
            missingFiles.isNotEmpty() -> "缺少或不完整：${missingFiles.joinToString()}"
            else -> "模型完整性校验失败"
        }
}

/** Standalone VoiceProfile inputs: the reference audio file plus its transcript. */
data class ReferenceAudioStatus(
    val ready: Boolean,
    val audioReady: Boolean,
    val referenceTextReady: Boolean,
    val referencePath: String,
) {
    val summary: String
        get() = when {
            !audioReady -> "缺少参考音频（reference.wav）"
            !referenceTextReady -> "参考文本（referenceText）为空，需填写与音频匹配的文字"
            else -> "参考音频与参考文本已就绪"
        }
}

/** Resolves deployable assets outside the APK so model binaries never enter Git. */
class ModelDirectoryResolver(context: Context) {
    companion object {
        const val ZIPVOICE_MODEL_ID = "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia"
        const val DEFAULT_REFERENCE_TEXT = "那还是三十六年前, 一九八七年. 我呢考上了武汉大学的计算机系."

        /** APK asset subtree carrying the standard embedded model bundle. */
        private const val BUNDLED_ASSET_ROOT = "bundled"
        private const val BUNDLED_MODEL_DIR = "$BUNDLED_ASSET_ROOT/models/zipvoice"
        private const val BUNDLED_REFERENCE = "$BUNDLED_ASSET_ROOT/voices/default/reference.wav"
    }

    private val appContext = context.applicationContext
    private val assets = appContext.assets
    private val externalRoot = appContext.getExternalFilesDir(null) ?: appContext.filesDir
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

    /** True when this APK carries the standard embedded ZipVoice bundle. */
    fun hasBundledModel(): Boolean =
        runCatching { assets.list(BUNDLED_MODEL_DIR)?.isNotEmpty() == true }.getOrDefault(false)

    /**
     * First-run extraction of the APK-embedded standard model (标配内置模型):
     * copies the bundled asset tree onto the deployable external layout when
     * the on-disk model is incomplete. A ready, user-managed install is never
     * overwritten; the default reference.wav is only written when missing so
     * user recordings under voices/<profileId>/ are never touched.
     *
     * @return true when anything was extracted and the status cache must be
     *         re-inspected.
     */
    @Synchronized
    fun extractBundledModelIfNeeded(): Boolean {
        if (!hasBundledModel()) return false
        if (layout().isReady()) return false
        var changed = false
        runCatching {
            changed = copyAssetDir(BUNDLED_MODEL_DIR, zipVoiceRoot)
            if (!referenceAudio.isFile) {
                referenceAudio.parentFile?.mkdirs()
                copyAssetFile(BUNDLED_REFERENCE, referenceAudio)
                changed = true
            }
        }.onFailure { android.util.Log.w("ModelDirectoryResolver", "bundled model extraction failed", it) }
        if (changed) cachedStatus = null
        return changed
    }

    @Synchronized
    fun inspect(forceIntegrityCheck: Boolean = false): ZipVoiceModelStatus {
        if (!forceIntegrityCheck) cachedStatus?.let { return it }
        // Fresh install on a bundled build: extract the embedded standard
        // model before reporting status so users never need a download.
        if (!layout().isReady()) extractBundledModelIfNeeded()
        val layout = layout()
        val missing = layout.missingFiles()
        val parsedManifest = readManifest()
        val checksumVerified = missing.isEmpty() && parsedManifest != null && verifyChecksum(layout, parsedManifest)
        val status = ZipVoiceModelStatus(
            ready = missing.isEmpty() && checksumVerified,
            rootPath = layout.root.absolutePath,
            missingFiles = missing,
            checksumVerified = checksumVerified,
            manifest = parsedManifest,
        )
        cachedStatus = status
        return status
    }

    /** Recursively copies an asset directory tree; true when bytes were written. */
    private fun copyAssetDir(assetDir: String, target: File): Boolean {
        val entries = assets.list(assetDir).orEmpty()
        if (entries.isEmpty()) return false
        var wrote = false
        target.mkdirs()
        for (entry in entries) {
            val assetPath = if (assetDir.isEmpty()) entry else "$assetDir/$entry"
            val destination = File(target, entry)
            val isDirectory = runCatching { assets.list(assetPath).orEmpty().isNotEmpty() }.getOrDefault(false)
            if (isDirectory) {
                wrote = copyAssetDir(assetPath, destination) || wrote
            } else {
                if (destination.isFile && destination.length() > 0) continue
                copyAssetFile(assetPath, destination)
                wrote = true
            }
        }
        return wrote
    }

    private fun copyAssetFile(assetPath: String, destination: File) {
        assets.open(assetPath).use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
            }
        }
    }

    /** Validates the VoiceProfile inputs (default reference audio + its transcript) independently of model status. */
    fun referenceAudioStatus(referenceText: String): ReferenceAudioStatus = ReferenceAudioStatus(
        audioReady = referenceAudio.isFile,
        referenceTextReady = referenceText.isNotBlank(),
        referencePath = referenceAudio.absolutePath,
        ready = referenceAudio.isFile && referenceText.isNotBlank(),
    )

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
