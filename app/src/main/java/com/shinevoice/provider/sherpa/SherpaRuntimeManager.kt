package com.shinevoice.provider.sherpa

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import com.shinevoice.core.log.AppLogger
import com.shinevoice.core.storage.ModelDirectoryResolver
import com.shinevoice.core.storage.ReferenceAudioLoader
import com.shinevoice.domain.tts.ProviderResult
import com.shinevoice.domain.tts.TtsError
import com.shinevoice.domain.tts.TtsErrorCode
import com.shinevoice.domain.tts.TtsRequest
import java.io.File

/** Owns one sherpa-onnx OfflineTts object at a time and recycles it per request. */
class SherpaRuntimeManager(
    private val modelResolver: ModelDirectoryResolver,
    private val referenceAudioLoader: ReferenceAudioLoader,
    private val logger: AppLogger,
) {
    private val lock = Any()
    private var tts: OfflineTts? = null

    fun initialize(): ProviderResult = synchronized(lock) {
        if (tts != null) return@synchronized ProviderResult.ok("ZipVoice Native Runtime 已初始化")

        val layout = modelResolver.layout()
        val missing = layout.missingFiles()
        if (missing.isNotEmpty()) {
            return@synchronized ProviderResult.failure(
                TtsError(
                    TtsErrorCode.ModelNotInstalled,
                    "ZipVoice 模型或参考音频未就绪：${missing.joinToString()}",
                    "root=${layout.root.absolutePath}",
                ),
            )
        }

        return@synchronized try {
            val zipVoice = OfflineTtsZipVoiceModelConfig(
                tokens = layout.tokens.absolutePath,
                encoder = layout.encoder.absolutePath,
                decoder = layout.decoder.absolutePath,
                vocoder = layout.vocoder.absolutePath,
                dataDir = layout.dataDirectory.absolutePath,
                lexicon = layout.lexicon.absolutePath,
                featScale = 0.1f,
                tShift = 0.5f,
                targetRms = 0.1f,
                guidanceScale = 1.0f,
            )
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    zipvoice = zipVoice,
                    // Keep the native ORT thread pool single-threaded. The bundled
                    // sherpa-onnx 1.13.x x86_64 runtime is unstable on repeated
                    // ZipVoice generations when it owns multiple worker threads.
                    numThreads = 1,
                    debug = false,
                    provider = "cpu",
                ),
                maxNumSentences = 1,
            )
            tts = OfflineTts(config = config)
            logger.i("ZipVoice Native Runtime initialized: ${layout.modelDirectory.absolutePath}")
            ProviderResult.ok("ZipVoice Native Runtime 已初始化")
        } catch (throwable: Throwable) {
            tts = null
            logger.e("ZipVoice Native Runtime initialization failed", throwable)
            ProviderResult.failure(
                TtsError(
                    TtsErrorCode.NativeRuntimeError,
                    "ZipVoice Native Runtime 初始化失败。",
                    throwable.message,
                ),
            )
        }
    }

    fun generate(request: TtsRequest): GeneratedAudio {
        val runtime = synchronized(lock) { tts }
            ?: throw IllegalStateException("ZipVoice Native Runtime is not initialized")
        return try {
            val layout = modelResolver.layout()
            val reference = referenceAudioLoader.load(
                File(request.extra[EXTRA_REFERENCE_AUDIO] ?: layout.referenceAudio.absolutePath),
            )
            val referenceText = request.extra[EXTRA_REFERENCE_TEXT].orEmpty()
            require(referenceText.isNotBlank()) { "referenceText must not be blank" }

            val generationConfig = GenerationConfig(
                speed = request.speed,
                referenceAudio = reference.samples,
                referenceSampleRate = reference.sampleRate,
                referenceText = referenceText,
                numSteps = request.extra[EXTRA_NUM_STEPS]?.toIntOrNull()?.coerceIn(1, 8) ?: 4,
                silenceScale = 0.2f,
            )
            runtime.generateWithConfig(request.text, generationConfig)
        } finally {
            // sherpa-onnx 1.13.x has a native lifetime bug when the same
            // OfflineTts pointer is used for repeated generateWithConfig calls:
            // the first result can succeed while a later call corrupts a
            // secondary allocation. Recycle the pointer after every request;
            // GeneratedAudio owns its copied samples and remains safe to save.
            synchronized(lock) {
                if (tts === runtime) {
                    tts = null
                    runCatching { runtime.release() }
                        .onFailure { logger.w("ZipVoice Native Runtime per-request release failed", it) }
                }
            }
        }
    }

    fun release() = synchronized(lock) {
        tts?.let { runtime ->
            runCatching { runtime.release() }
                .onFailure { logger.w("ZipVoice Native Runtime release failed", it) }
        }
        tts = null
        logger.i("ZipVoice Native Runtime released")
    }

    companion object {
        const val EXTRA_REFERENCE_AUDIO = "referenceAudioPath"
        const val EXTRA_REFERENCE_TEXT = "referenceText"
        const val EXTRA_NUM_STEPS = "numSteps"
    }
}
