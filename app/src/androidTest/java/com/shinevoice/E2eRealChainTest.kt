package com.shinevoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinevoice.core.storage.ModelDirectoryResolver
import com.shinevoice.domain.tts.TtsRequest
import com.shinevoice.domain.tts.TtsLanguageCatalog
import com.shinevoice.domain.tts.TtsResult
import com.shinevoice.domain.tts.TtsVoice
import com.shinevoice.provider.androidtts.AndroidSystemTtsProvider
import com.shinevoice.provider.minimax.MiniMaxLanguageMapper
import com.shinevoice.provider.minimax.MiniMaxProvider
import com.shinevoice.provider.sherpa.SherpaZipVoiceProvider
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Real-chain E2E tests executed on a device/emulator via the app's own
 * wiring (ShineVoiceApplication). No mocks: ZipVoice runs real Native
 * inference, Android System TTS uses real engines/voices, and the MiniMax
 * test talks to the live API when the host test runner supplies a credential
 * through the process-only instrumentation argument (never committed,
 * logged, or written to the project).
 *
 * Run examples:
 *   ./gradlew :app:connectedDebugAndroidTest
 *   The live credential is injected by the local acceptance runner only.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class E2eRealChainTest {
    private val app: ShineVoiceApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShineVoiceApplication

    private fun args(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }

    private fun isWav(file: File): Boolean {
        if (!file.isFile || file.length() < 44) return false
        val header = ByteArray(12)
        java.io.RandomAccessFile(file, "r").use { it.readFully(header) }
        return String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
    }

    private fun pssKb(): Int = runCatching {
        android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }.totalPss
    }.getOrDefault(-1)

    private fun voiceFingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .take(6)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class SpeedObservation(
        val speed: Float,
        val result: TtsResult,
        val file: File,
    )

    private fun assertSpeedOrdering(label: String, observations: List<SpeedObservation>) {
        val bySpeed = observations.associateBy { it.speed }
        val slow = bySpeed[0.5f]?.result?.durationMs
        val normal = bySpeed[1.0f]?.result?.durationMs
        val fast = bySpeed[2.0f]?.result?.durationMs
        assertTrue("$label missing 0.5x duration", slow != null && slow > 0L)
        assertTrue("$label missing 1.0x duration", normal != null && normal > 0L)
        assertTrue("$label missing 2.0x duration", fast != null && fast > 0L)
        assertTrue("$label 0.5x was not slower: $slow <= $normal", slow!! > normal!!)
        assertTrue("$label 2.0x was not faster: $fast >= $normal", fast!! < normal)
        println(
            "E2E_SPEED_ORDER label=$label durationMs(0.5/1.0/2.0)=" +
                "$slow/$normal/$fast ratios=" +
                "${"%.3f".format(slow.toDouble() / normal)} / ${"%.3f".format(fast.toDouble() / normal)}",
        )
    }

    private suspend fun runZipVoiceSpeedMatrix(language: String, text: String): List<SpeedObservation> {
        val speeds = args("zipSpeed")?.toFloatOrNull()?.let(::listOf) ?: listOf(0.5f, 1.0f, 2.0f)
        val observations = speeds.map { speed ->
            val request = TtsRequest(
                taskId = "e2e-zv-matrix-$language-$speed-${UUID.randomUUID()}",
                text = text,
                providerId = SherpaZipVoiceProvider.PROVIDER_ID,
                voiceId = SherpaZipVoiceProvider.DEFAULT_VOICE_ID,
                language = language,
                speed = speed,
                extra = mapOf(
                    SherpaZipVoiceProvider.EXTRA_REFERENCE_AUDIO to app.modelResolver.referenceAudio.absolutePath,
                    SherpaZipVoiceProvider.EXTRA_REFERENCE_TEXT to ModelDirectoryResolver.DEFAULT_REFERENCE_TEXT,
                    SherpaZipVoiceProvider.EXTRA_NUM_STEPS to "4",
                ),
            )
            val result = app.ttsManager.synthesize(request)
            assertTrue(
                "ZipVoice $language ${speed}x failed: ${result.error?.userMessage}",
                result.success,
            )
            val file = File(result.audioFile!!)
            assertTrue("ZipVoice $language ${speed}x output is not WAV", isWav(file))
            assertTrue("ZipVoice $language ${speed}x duration missing", (result.durationMs ?: 0L) > 0L)
            val persisted = app.database.generationHistoryDao().getByIds(listOf(request.taskId)).firstOrNull()
            assertTrue("ZipVoice $language ${speed}x history row missing", persisted != null)
            persisted?.let {
                assertEquals(language, it.language)
                assertEquals(speed, it.speed, 0.0001f)
            }
            println(
                "E2E_ZIPVOICE_LANGUAGE language=$language speed=$speed elapsedMs=${result.elapsedMs} " +
                    "durationMs=${result.durationMs} bytes=${file.length()} playback=WAV_HEADER_OK",
            )
            SpeedObservation(speed, result, file)
        }
        if (speeds == listOf(0.5f, 1.0f, 2.0f)) {
            assertSpeedOrdering("ZipVoice $language", observations)
        }
        return observations
    }

    private suspend fun runSystemTtsSpeedMatrix(
        provider: AndroidSystemTtsProvider,
        language: String,
        voice: TtsVoice,
        text: String,
    ): List<SpeedObservation> {
        val observations = listOf(0.5f, 1.0f, 2.0f).map { speed ->
            val request = TtsRequest(
                taskId = "e2e-sys-matrix-$language-$speed-${UUID.randomUUID()}",
                text = text,
                providerId = AndroidSystemTtsProvider.PROVIDER_ID,
                voiceId = voice.id,
                language = language,
                speed = speed,
            )
            val result = app.ttsManager.synthesize(request)
            assertTrue(
                "System TTS $language ${speed}x failed: ${result.error?.userMessage}",
                result.success,
            )
            assertEquals("System TTS selected the wrong voice", voice.id, result.voiceId)
            val file = File(result.audioFile!!)
            assertTrue("System TTS $language ${speed}x output is not WAV", isWav(file))
            assertTrue("System TTS $language ${speed}x duration missing", (result.durationMs ?: 0L) > 0L)
            println(
                "E2E_SYSTEM_TTS language=$language speed=$speed voiceLength=${voice.id.length} " +
                    "voiceFingerprint=${voiceFingerprint(voice.id)} elapsedMs=${result.elapsedMs} " +
                    "durationMs=${result.durationMs} bytes=${file.length()} playback=WAV_HEADER_OK",
            )
            SpeedObservation(speed, result, file)
        }
        assertSpeedOrdering("System TTS $language", observations)
        return observations
    }

    private suspend fun ensureCloudVoice(provider: MiniMaxProvider, purpose: String): String {
        app.voiceProfileManager.ensureDefaultProfile()
        val profiles = app.voiceProfileManager.observeProfiles().first()
        var voiceId = profiles.firstOrNull { !it.minimaxVoiceId.isNullOrBlank() }?.minimaxVoiceId
        if (voiceId == null) {
            // A clean test install has not necessarily opened the model screen
            // yet. Trigger the app's normal bundled-asset extraction so the
            // official reference.wav is available for the real clone request.
            app.modelResolver.extractBundledModelIfNeeded()
            val reference = args("cloneRef")?.let(::File) ?: app.modelResolver.referenceAudio
            assertTrue("$purpose reference audio missing: ${reference.path}", reference.isFile)
            val clone = provider.cloneVoice(
                com.shinevoice.domain.tts.VoiceCloneRequest(
                    voiceProfileId = "e2e-$purpose-clone",
                    referenceAudioPath = reference.absolutePath,
                    referenceText = ModelDirectoryResolver.DEFAULT_REFERENCE_TEXT,
                ),
            )
            assertTrue("$purpose cloud clone failed: ${clone.error?.userMessage}", clone.success)
            voiceId = clone.voiceId
            assertTrue("$purpose cloud clone returned no voice", !voiceId.isNullOrBlank())
            profiles.firstOrNull()?.let { profile ->
                app.voiceProfileManager.updateCloudBinding(profile.id, voiceId!!)
            }
            println(
                "E2E_MINIMAX_CLONE purpose=$purpose voiceLength=${voiceId!!.length} " +
                    "voiceFingerprint=${voiceFingerprint(voiceId!!)}",
            )
        }
        return voiceId!!
    }

    /** 20 consecutive real ZipVoice generations with timing/RTF/PSS statistics. */
    @Test
    fun test01_zipVoiceTwentyRealGenerations() = runBlocking {
        val status = app.modelResolver.inspect(forceIntegrityCheck = true)
        assertTrue("model not ready: ${status.summary}", status.ready)
        app.ttsManager.initialize(SherpaZipVoiceProvider.PROVIDER_ID).also {
            assertTrue("init failed: ${it.message}", it.success)
        }
        val pssBefore = pssKb()
        val elapsedList = mutableListOf<Long>()
        val rtfList = mutableListOf<Double>()
        var failures = 0
        repeat(20) { index ->
            val request = TtsRequest(
                taskId = "e2e-zv-${index + 1}-${UUID.randomUUID()}",
                text = "世恒哥，这是 ShineVoice 的本地中文声音克隆测试，第${index + 1}次连续真实生成。",
                providerId = SherpaZipVoiceProvider.PROVIDER_ID,
                voiceId = SherpaZipVoiceProvider.DEFAULT_VOICE_ID,
                speed = 1.0f,
                extra = mapOf(
                    SherpaZipVoiceProvider.EXTRA_REFERENCE_AUDIO to app.modelResolver.referenceAudio.absolutePath,
                    SherpaZipVoiceProvider.EXTRA_REFERENCE_TEXT to ModelDirectoryResolver.DEFAULT_REFERENCE_TEXT,
                    SherpaZipVoiceProvider.EXTRA_NUM_STEPS to "4",
                ),
            )
            val result = app.ttsManager.synthesize(request)
            if (result.success) {
                elapsedList += result.elapsedMs
                result.rtf?.let { rtfList += it }
                assertTrue("not a wav: ${result.audioFile}", isWav(File(result.audioFile!!)))
            } else {
                failures++
                println("E2E ZipVoice failure #$index: ${result.error?.userMessage} / ${result.error?.causeMessage}")
            }
        }
        val pssAfter = pssKb()
        println(
            "E2E_STABILITY_20 successes=${20 - failures}/20 failures=$failures " +
                "avgMs=${elapsedList.average().toLong()} maxMs=${elapsedList.max()} " +
                "avgRtf=${"%.3f".format(rtfList.average())} maxRtf=${"%.3f".format(rtfList.max())} " +
                "pssBeforeKb=$pssBefore pssAfterKb=$pssAfter deltaKb=${pssAfter - pssBefore}",
        )
        assertEquals("ZipVoice 20-run stability failures", 0, failures)
    }

    /** Real engine enumeration + language-aware synthesis via Android System TTS. */
    @Test
    fun test02_androidSystemTtsEnginesAndSynthesis() = runBlocking {
        val provider = app.providerRegistry.get(AndroidSystemTtsProvider.PROVIDER_ID)
            as? AndroidSystemTtsProvider
            ?: error("system tts provider missing")
        val engines = provider.availableEngines()
        assertTrue("no TTS engines installed", engines.isNotEmpty())
        println("E2E SystemTTS engines=${engines.joinToString { "${it.label}(${if (it.isSystemDefault) "default" else ""})" }}")
        val init = provider.initialize()
        assertTrue("system tts init failed: ${init.message}", init.success)
        try {
            val voices = provider.getVoices()
            assertTrue("no system voices for default engine", voices.isNotEmpty())
            val chineseVoice = voices.firstOrNull { it.language == TtsLanguageCatalog.ZH_CN.id }
            val englishVoice = voices.firstOrNull { it.language == TtsLanguageCatalog.EN_US.id }
            println("E2E SystemTTS voiceCount=${voices.size}")
            var testedLanguages = 0
            if (chineseVoice != null) {
                runSystemTtsSpeedMatrix(
                    provider = provider,
                    language = TtsLanguageCatalog.ZH_CN.id,
                    voice = chineseVoice,
                    text = "这是系统语音引擎的真实中文语速验收。每个档位都必须输出可播放的 WAV，并且慢速明显更长、快速明显更短。",
                )
                testedLanguages++
            } else {
                println("E2E SystemTTS language=${TtsLanguageCatalog.ZH_CN.id} UNSUPPORTED_BY_TEST_DEVICE")
            }
            if (englishVoice != null) {
                runSystemTtsSpeedMatrix(
                    provider = provider,
                    language = TtsLanguageCatalog.EN_US.id,
                    voice = englishVoice,
                    text = "This is a real system TTS speed acceptance test. Every speed must produce a playable WAV, with a clearly longer slow result and a clearly shorter fast result.",
                )
                testedLanguages++
            } else {
                println("E2E SystemTTS language=${TtsLanguageCatalog.EN_US.id} UNSUPPORTED_BY_TEST_DEVICE")
            }
            assertTrue("no supported Chinese or English system language on test device", testedLanguages > 0)
            val caps = provider.getCapabilities()
            println("E2E SystemTTS supportsOffline=${caps.supportsOffline} testedLanguages=$testedLanguages")
        } finally {
            provider.release()
        }
    }

    /**
     * Full real MiniMax chain: BYOK save -> connection probe -> upload ->
     * voice_clone -> t2a_v2 -> wav on disk. Requires -e minimaxApiKey and
     * optionally -e cloneRef (>=10s reference wav on device).
     */
    @Test
    fun test03_minimaxRealChain() = runBlocking {
        val apiKey = args("minimaxApiKey")
            ?: return@runBlocking println("E2E MiniMax SKIPPED (no -e minimaxApiKey)")
        app.minimaxConfig.save("", apiKey, com.shinevoice.data.settings.MiniMaxRegion.CN)
        val provider = app.providerRegistry.get(MiniMaxProvider.PROVIDER_ID) as MiniMaxProvider
        val probe = provider.validateConfig()
        println(
            "E2E_MINIMAX_PREFLIGHT success=${probe.success} code=${probe.error?.code} " +
                "message=${probe.message} cause=${probe.error?.causeMessage?.take(120)}",
        )
        assertTrue("MiniMax connection failed: ${probe.message}", probe.success)
        println("E2E_MINIMAX_PREFLIGHT credentialLoaded=true connection=success")
        val voiceId = ensureCloudVoice(provider, "single-chain")

        val started = System.nanoTime()
        val language = TtsLanguageCatalog.ZH_CN.id
        val text = "这是云端高清的真实中文合成测试。"
        val result = app.ttsManager.synthesize(
            TtsRequest(
                taskId = "e2e-mm-${UUID.randomUUID()}",
                text = text,
                providerId = MiniMaxProvider.PROVIDER_ID,
                voiceId = voiceId,
                language = language,
                speed = 1.0f,
            ),
        )
        val wallMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("MiniMax t2a failed: ${result.error?.userMessage} / ${result.error?.causeMessage}", result.success)
        val wav = File(result.audioFile!!)
        assertTrue("MiniMax output not wav: ${wav.length()}B", isWav(wav))
        assertTrue("MiniMax duration missing", (result.durationMs ?: 0L) > 0L)
        println(
                "E2E_MINIMAX_REAL task=${result.taskId} language=$language " +
                "languageBoost=${MiniMaxLanguageMapper.toLanguageBoost(language)} speed=1.0 " +
                "textLength=${text.length} voiceIdLength=${voiceId.length} " +
                "voiceFingerprint=${voiceFingerprint(voiceId)} success=${result.success} " +
                "wallMs=$wallMs providerElapsedMs=${result.elapsedMs} audioBytes=${wav.length()} " +
                "durationMs=${result.durationMs} model=${result.model}",
        )
    }

    /**
     * STRICT three-way provider cycle: 本地生成 -> 系统语音 -> 云端高清,
     * repeated for 7 rounds = 21 REAL generations. Every run must succeed,
     * echo the requested providerId + voiceId (no cross-routing), and write a
     * valid WAV. A cloud rate-limit is retried once after backoff; any other
     * failure is FATAL (never waved through as non-fatal).
     */
    @Test
    fun test04_providerStrictThreeWayCycle() = runBlocking {
        val sysProvider = app.providerRegistry.get(AndroidSystemTtsProvider.PROVIDER_ID) as AndroidSystemTtsProvider
        assertTrue("system tts init failed", sysProvider.initialize().success)
        val sysVoice = sysProvider.getVoices()
            .firstOrNull { it.language == TtsLanguageCatalog.ZH_CN.id }
            ?.id
            ?: sysProvider.getVoices().firstOrNull()?.id
        assertTrue("no system voice", sysVoice != null)
        val cloudKey = args("minimaxApiKey")
            ?: return@runBlocking println("E2E 3-way cycle SKIPPED (no -e minimaxApiKey)")
        app.minimaxConfig.save("", cloudKey, com.shinevoice.data.settings.MiniMaxRegion.CN)
        val cloudProvider = app.providerRegistry.get(MiniMaxProvider.PROVIDER_ID) as MiniMaxProvider
        assertTrue("cloud pre-flight failed", cloudProvider.validateConfig().success)
        val cloudVoice = ensureCloudVoice(cloudProvider, "three-way")

        val rounds = 7
        var failures = 0
        val log = StringBuilder()
        repeat(rounds) { round ->
            val specs = listOf(
                Triple(SherpaZipVoiceProvider.PROVIDER_ID, SherpaZipVoiceProvider.DEFAULT_VOICE_ID, "本地生成"),
                Triple(AndroidSystemTtsProvider.PROVIDER_ID, sysVoice!!, "系统语音"),
                Triple(MiniMaxProvider.PROVIDER_ID, cloudVoice!!, "云端高清"),
            )
            for ((providerId, voiceId, label) in specs) {
                val request = TtsRequest(
                    taskId = "e2e-3way-r${round + 1}-${label}-${UUID.randomUUID()}",
                    text = "三方式切换第${round + 1}轮，$label 真实生成测试。",
                    providerId = providerId,
                    voiceId = voiceId,
                    language = TtsLanguageCatalog.ZH_CN.id,
                    extra = if (providerId == SherpaZipVoiceProvider.PROVIDER_ID) {
                        mapOf(
                            SherpaZipVoiceProvider.EXTRA_REFERENCE_AUDIO to app.modelResolver.referenceAudio.absolutePath,
                            SherpaZipVoiceProvider.EXTRA_REFERENCE_TEXT to ModelDirectoryResolver.DEFAULT_REFERENCE_TEXT,
                            SherpaZipVoiceProvider.EXTRA_NUM_STEPS to "4",
                        )
                    } else {
                        emptyMap()
                    },
                )
                var result = app.ttsManager.synthesize(request)
                if (!result.success &&
                    result.error?.code == com.shinevoice.domain.tts.TtsErrorCode.ApiRateLimited
                ) {
                    println("E2E 3-way rate-limited on $label, backing off 6s and retrying once")
                    kotlinx.coroutines.delay(6_000)
                    result = app.ttsManager.synthesize(request.copy(taskId = request.taskId + "-retry"))
                }
                val providerOk = result.providerId == providerId
                val voiceOk = result.voiceId == null || result.voiceId == voiceId
                val wavOk = result.success && isWav(File(result.audioFile!!))
                if (!result.success || !providerOk || !voiceOk || !wavOk) {
                    failures++
                    println(
                        "E2E 3-way FAILURE round=${round + 1} label=$label success=${result.success} " +
                            "providerEcho=${result.providerId} " +
                                "voiceEchoLength=${result.voiceId?.length} " +
                                "voiceEchoFingerprint=${result.voiceId?.let(::voiceFingerprint)} " +
                            "err=${result.error?.userMessage}",
                    )
                }
                log.append(
                    "round=${round + 1} want=$label($providerId) got=${result.providerId} " +
                        "voiceLength=${result.voiceId?.length} " +
                        "voiceFingerprint=${result.voiceId?.let(::voiceFingerprint)} " +
                        "ok=${result.success} ${result.elapsedMs}ms " +
                        "file=${result.audioFile?.substringAfterLast('/')}\n",
                )
                if (providerId == MiniMaxProvider.PROVIDER_ID) kotlinx.coroutines.delay(2_000)
            }
        }
        println("E2E_3WAY_CYCLE_LOG\n$log")
        assertEquals("strict 3-way cycle failures", 0, failures)
        sysProvider.release()
        println("E2E strict 3-way provider cycle OK ($rounds rounds x3 = ${rounds * 3} real generations)")
    }

    /**
     * ZipVoice 50-run memory trend with warmup: init -> 3 warmup generations ->
     * settle (GC + delay) -> PSS baseline -> 50 generations with PSS/heap
     * checkpoints at run 5/10/20/30/40/50. Distinguishes first-inference lazy
     * allocation from a linear native leak.
     */
    @Test
    fun test07_zipVoiceFiftyRunMemoryTrend() = runBlocking {
        val status = app.modelResolver.inspect(forceIntegrityCheck = true)
        assertTrue("model not ready: ${status.summary}", status.ready)
        app.ttsManager.initialize(SherpaZipVoiceProvider.PROVIDER_ID).also {
            assertTrue("init failed: ${it.message}", it.success)
        }
        fun request(index: Int) = TtsRequest(
            taskId = "e2e-mem-${index}-${UUID.randomUUID()}",
            text = "内存趋势测试，第${index}次本地真实生成，观察进程内存变化。",
            providerId = SherpaZipVoiceProvider.PROVIDER_ID,
            voiceId = SherpaZipVoiceProvider.DEFAULT_VOICE_ID,
            extra = mapOf(
                SherpaZipVoiceProvider.EXTRA_REFERENCE_AUDIO to app.modelResolver.referenceAudio.absolutePath,
                SherpaZipVoiceProvider.EXTRA_REFERENCE_TEXT to ModelDirectoryResolver.DEFAULT_REFERENCE_TEXT,
                SherpaZipVoiceProvider.EXTRA_NUM_STEPS to "4",
            ),
        )
        // Warmup: real generations so lazy arena/tensor allocation settles.
        repeat(3) { warm ->
            val result = app.ttsManager.synthesize(request(warm))
            assertTrue("warmup #$warm failed: ${result.error?.userMessage}", result.success)
        }
        // Settle: let GC run, then idle so the runtime reaches steady state.
        repeat(3) { Runtime.getRuntime().gc(); kotlinx.coroutines.delay(400) }
        kotlinx.coroutines.delay(2_000)

        fun checkpoint(at: Int): String {
            val mi = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(mi)
            val javaUsedKb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024
            val nativeAllocKb = android.os.Debug.getNativeHeapAllocatedSize() / 1024
            return "PSS=${mi.totalPss}KB javaHeap=${javaUsedKb}KB nativeHeap=${nativeAllocKb}KB"
        }

        val baseline = checkpoint(0)
        println("E2E_MEM_CHECKPOINT run=0(warm) $baseline")
        val elapsedList = mutableListOf<Long>()
        val rtfList = mutableListOf<Double>()
        var failures = 0
        val checkpoints = sortedSetOf(5, 10, 20, 30, 40, 50)
        repeat(50) { index ->
            val result = app.ttsManager.synthesize(request(index + 1))
            if (result.success) {
                elapsedList += result.elapsedMs
                result.rtf?.let { rtfList += it }
                assertTrue("not a wav: ${result.audioFile}", isWav(File(result.audioFile!!)))
            } else {
                failures++
                println("E2E mem failure #${index + 1}: ${result.error?.userMessage} / ${result.error?.causeMessage}")
            }
            val runNo = index + 1
            if (runNo in checkpoints) {
                Runtime.getRuntime().gc()
                kotlinx.coroutines.delay(600)
                println("E2E_MEM_CHECKPOINT run=$runNo ${checkpoint(runNo)}")
            }
        }
        val trend = elapsedList.chunked(10).mapIndexed { i, chunk -> "block${i + 1}avg=${chunk.average().toLong()}ms" }
        println(
            "E2E_MEM_50_SUMMARY successes=${50 - failures}/50 failures=$failures " +
                "avgMs=${if (elapsedList.isEmpty()) "-" else elapsedList.average().toLong()} " +
                "maxMs=${if (elapsedList.isEmpty()) "-" else elapsedList.max()} " +
                "avgRtf=${if (rtfList.isEmpty()) "-" else "%.3f".format(rtfList.average())} " +
                "maxRtf=${if (rtfList.isEmpty()) "-" else "%.3f".format(rtfList.max())} " +
                "trend=${trend.joinToString(" ")}",
        )
        assertEquals("ZipVoice 50-run failures", 0, failures)
    }

    /** Real bundled ZipVoice inference and speed ordering for both model languages. */
    @Test
    fun test08_zipVoiceChineseAndEnglishRealInference(): Unit = runBlocking {
        val status = app.modelResolver.inspect(forceIntegrityCheck = true)
        assertTrue("model not ready: " + status.summary, status.ready)
        assertTrue("ZipVoice init failed", app.ttsManager.initialize(SherpaZipVoiceProvider.PROVIDER_ID).success)
        runZipVoiceSpeedMatrix(
            language = TtsLanguageCatalog.ZH_CN.id,
            text = "这是本地 ZipVoice 模型的中文真实推理和语速验收测试。每个速度档位都必须写出可播放的 WAV 音频。",
        )
        runZipVoiceSpeedMatrix(
            language = TtsLanguageCatalog.EN_US.id,
            text = "This is a real English inference and speed acceptance test from the bundled ZipVoice model. Every speed must produce a playable WAV audio file.",
        )
    }

    /**
     * Real MiniMax contract test: one cloned voice binding, seven language/rate
     * combinations, and no voice cloning during language changes.
     */
    @Test
    fun test09_minimaxMultilingualSameVoiceAndSpeed() = runBlocking {
        val apiKey = args("minimaxApiKey")
            ?: return@runBlocking println("E2E MiniMax multilingual SKIPPED (no -e minimaxApiKey)")
        app.minimaxConfig.save("", apiKey, com.shinevoice.data.settings.MiniMaxRegion.CN)
        val provider = app.providerRegistry.get(MiniMaxProvider.PROVIDER_ID) as MiniMaxProvider
        val probe = provider.validateConfig()
        println(
            "E2E_MINIMAX_PREFLIGHT success=${probe.success} code=${probe.error?.code} " +
                "message=${probe.message} cause=${probe.error?.causeMessage?.take(120)}",
        )
        assertTrue("MiniMax multilingual pre-flight failed: ${probe.message}", probe.success)
        val stableVoiceId = ensureCloudVoice(provider, "multilingual")
        val cases = listOf(
            Triple(TtsLanguageCatalog.ZH_CN.id, "大家好，这是 ShineVoice 多语言声音测试。", 0.75f),
            Triple(TtsLanguageCatalog.ZH_CN.id, "大家好，这是 ShineVoice 多语言声音测试。", 1.0f),
            Triple(TtsLanguageCatalog.ZH_CN.id, "大家好，这是 ShineVoice 多语言声音测试。", 1.5f),
            Triple(TtsLanguageCatalog.EN_US.id, "Hello everyone, this is a multilingual ShineVoice voice test.", 0.75f),
            Triple(TtsLanguageCatalog.EN_US.id, "Hello everyone, this is a multilingual ShineVoice voice test.", 1.0f),
            Triple(TtsLanguageCatalog.EN_US.id, "Hello everyone, this is a multilingual ShineVoice voice test.", 1.5f),
            Triple(TtsLanguageCatalog.JA_JP.id, "こんにちは、これは ShineVoice の多言語音声テストです。", 1.0f),
        )
        val observedVoiceIds = mutableListOf<String>()
        val successfulLanguages = mutableMapOf<String, Int>()
        for (case in cases) {
            val request = TtsRequest(
                taskId = "e2e-mm-language-" + case.first + "-" + case.third + "-" + UUID.randomUUID(),
                text = case.second,
                providerId = MiniMaxProvider.PROVIDER_ID,
                voiceId = stableVoiceId,
                language = case.first,
                speed = case.third,
            )
            var result = app.ttsManager.synthesize(request)
            if (!result.success && result.error?.code == com.shinevoice.domain.tts.TtsErrorCode.ApiRateLimited) {
                println("E2E_MINIMAX_LANGUAGE task=${request.taskId} classification=BLOCKED_RATE_LIMIT retry=once")
                kotlinx.coroutines.delay(6_000)
                result = app.ttsManager.synthesize(request.copy(taskId = request.taskId + "-retry"))
            }
            if (!result.success && case.first == TtsLanguageCatalog.JA_JP.id && isOptionalJapaneseUnsupported(result)) {
                println(
                    "E2E_MINIMAX_LANGUAGE task=${request.taskId} language=${case.first} " +
                        "languageBoost=${MiniMaxLanguageMapper.toLanguageBoost(case.first)} speed=${case.third} " +
                        "classification=UNSUPPORTED_BY_ACCOUNT error=${result.error?.userMessage}",
                )
                continue
            }
            assertTrue(
                "MiniMax " + case.first + " " + case.third + "x failed: " + result.error?.userMessage,
                result.success,
            )
            val wav = File(result.audioFile!!)
            assertTrue("MiniMax output is not WAV", isWav(wav))
            assertTrue("MiniMax duration missing", (result.durationMs ?: 0L) > 0L)
            assertEquals(stableVoiceId, result.voiceId)
            observedVoiceIds += result.voiceId.orEmpty()
            successfulLanguages[case.first] = (successfulLanguages[case.first] ?: 0) + 1
            println(
                "E2E_MINIMAX_LANGUAGE task=${request.taskId} language=${case.first} " +
                    "languageBoost=${MiniMaxLanguageMapper.toLanguageBoost(case.first)} speed=${case.third} " +
                    "textLength=${case.second.length} voiceIdLength=${stableVoiceId.length} " +
                    "voiceFingerprint=${voiceFingerprint(stableVoiceId)} success=${result.success} " +
                    "audioBytes=${wav.length()} durationMs=${result.durationMs} elapsedMs=${result.elapsedMs}",
            )
        }
        assertEquals("MiniMax Chinese speed matrix incomplete", 3, successfulLanguages[TtsLanguageCatalog.ZH_CN.id])
        assertEquals("MiniMax English speed matrix incomplete", 3, successfulLanguages[TtsLanguageCatalog.EN_US.id])
        assertTrue("voiceId changed across language/rate requests", observedVoiceIds.all { it == stableVoiceId })
        assertTrue("MiniMax matrix produced no successful voice result", observedVoiceIds.isNotEmpty())
        println(
            "E2E_MINIMAX_MULTILINGUAL sameVoiceFingerprint=" + voiceFingerprint(stableVoiceId) +
                " voiceIdLength=${stableVoiceId.length} languages=${successfulLanguages.keys.joinToString(",")}",
        )
    }

    private fun isOptionalJapaneseUnsupported(result: com.shinevoice.domain.tts.TtsResult): Boolean {
        val message = "${result.error?.userMessage} ${result.error?.causeMessage}".lowercase()
        return result.error?.code == com.shinevoice.domain.tts.TtsErrorCode.UnsupportedLanguage ||
            message.contains("language") || message.contains("语言") ||
            message.contains("japanese") || message.contains("日本語")
    }

    /** A wrong API key must surface a Chinese business error, never a raw stack. */
    @Test
    fun test05_minimaxWrongKeyRejected() = runBlocking {
        val provider = app.providerRegistry.get(MiniMaxProvider.PROVIDER_ID) as MiniMaxProvider
        app.minimaxConfig.save("", "invalid-key-e2e-test-000000", com.shinevoice.data.settings.MiniMaxRegion.CN)
        val probe = provider.validateConfig()
        assertTrue("wrong key should fail", !probe.success)
        val message = probe.error?.userMessage.orEmpty()
        println("E2E MiniMax wrong-key message: $message")
        assertTrue(
            "error should be a business message, got: $message",
            message.contains("Key") || message.contains("无效") || message.contains("权限") || message.contains("鉴权"),
        )
        assertTrue("must not leak the key itself", !message.contains("invalid-key-e2e"))
    }

    /**
     * Offline path: with airplane mode on (toggled externally via
     * `adb shell cmd connectivity airplane-mode enable`), synthesis must map
     * to NetworkUnavailable with a Chinese user message.
     */
    @Test
    fun test06_minimaxOfflineMapsToNetworkError() = runBlocking {
        val cm = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val online = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (online) {
            println("E2E offline test SKIPPED (network is up; enable airplane mode and re-run)")
            return@runBlocking
        }
        val provider = app.providerRegistry.get(MiniMaxProvider.PROVIDER_ID) as MiniMaxProvider
        val result = provider.synthesize(
            TtsRequest(
                taskId = "e2e-offline-${UUID.randomUUID()}",
                text = "断网测试。",
                providerId = MiniMaxProvider.PROVIDER_ID,
                voiceId = "svplaceholder0000",
            ),
        )
        assertTrue("offline synthesis must fail", !result.success)
        val error = result.error
        println("E2E offline error: code=${error?.code} msg=${error?.userMessage}")
        assertTrue(
            "expected NetworkUnavailable/timeout, got ${error?.code}",
            error?.code == com.shinevoice.domain.tts.TtsErrorCode.NetworkUnavailable ||
                error?.code == com.shinevoice.domain.tts.TtsErrorCode.GenerationTimeout,
        )
    }
}
