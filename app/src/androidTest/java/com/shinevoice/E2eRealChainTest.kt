package com.shinevoice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinevoice.core.storage.ModelDirectoryResolver
import com.shinevoice.domain.tts.TtsRequest
import com.shinevoice.provider.androidtts.AndroidSystemTtsProvider
import com.shinevoice.provider.minimax.MiniMaxProvider
import com.shinevoice.provider.sherpa.SherpaZipVoiceProvider
import java.io.File
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
 * test talks to the live API when a key is passed via
 * `am instrument -e minimaxApiKey <key>` (never committed anywhere).
 *
 * Run examples:
 *   ./gradlew :app:connectedDebugAndroidTest
 *   adb shell am instrument -w -e class com.shinevoice.E2eRealChainTest \
 *     -e minimaxApiKey <key> \
 *     -e cloneRef /sdcard/Android/data/com.shinevoice.debug/files/voices/minimax-e2e/reference.wav \
 *     com.shinevoice.debug.test/androidx.test.runner.AndroidJUnitRunner
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

    /** Real engine enumeration + Chinese voice synthesis via Android System TTS. */
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
        val voices = provider.getVoices()
        assertTrue("no Chinese voices for default engine", voices.isNotEmpty())
        println("E2E SystemTTS chineseVoices=${voices.size} first=${voices.first().displayName}")
        val request = TtsRequest(
            taskId = "e2e-sys-${UUID.randomUUID()}",
            text = "这是系统语音引擎的真实中文朗读测试。",
            providerId = AndroidSystemTtsProvider.PROVIDER_ID,
            voiceId = voices.first().id,
            speed = 1.0f,
        )
        val result = app.ttsManager.synthesize(request)
        assertTrue("system tts synthesis failed: ${result.error?.userMessage}", result.success)
        assertTrue("system tts output not wav", isWav(File(result.audioFile!!)))
        val caps = provider.getCapabilities()
        println("E2E SystemTTS supportsOffline=${caps.supportsOffline} voice=${voices.first().id}")
        provider.release()
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
        val cloneRef = args("cloneRef")?.let(::File)
        app.minimaxConfig.save("", apiKey, com.shinevoice.data.settings.MiniMaxRegion.CN)
        val provider = app.providerRegistry.get(MiniMaxProvider.PROVIDER_ID) as MiniMaxProvider
        val probe = provider.validateConfig()
        assertTrue("MiniMax connection failed: ${probe.message}", probe.success)
        println("E2E MiniMax connection OK: ${probe.message}")

        var voiceId = app.voiceProfileManager.observeProfiles().first()
            .firstOrNull { profile -> !profile.minimaxVoiceId.isNullOrBlank() }
            ?.minimaxVoiceId
        if (voiceId == null && cloneRef != null && cloneRef.isFile) {
            val clone = provider.cloneVoice(
                com.shinevoice.domain.tts.VoiceCloneRequest(
                    voiceProfileId = "e2e-clone",
                    referenceAudioPath = cloneRef.absolutePath,
                    referenceText = "",
                ),
            )
            assertTrue("clone failed: ${clone.error?.userMessage}", clone.success)
            voiceId = clone.voiceId
            println("E2E MiniMax cloned voiceIdLength=${voiceId?.length}")
        }
        assertTrue("no cloud voice available (no binding and no cloneRef)", voiceId != null)

        val started = System.nanoTime()
        val result = app.ttsManager.synthesize(
            TtsRequest(
                taskId = "e2e-mm-${UUID.randomUUID()}",
                text = "这是云端高清的真实中文合成测试。",
                providerId = MiniMaxProvider.PROVIDER_ID,
                voiceId = voiceId,
                speed = 1.0f,
            ),
        )
        val wallMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("MiniMax t2a failed: ${result.error?.userMessage} / ${result.error?.causeMessage}", result.success)
        val wav = File(result.audioFile!!)
        assertTrue("MiniMax output not wav: ${wav.length()}B", isWav(wav))
        println(
            "E2E MiniMax t2a OK wallMs=$wallMs providerElapsedMs=${result.elapsedMs} " +
                "bytes=${wav.length()} model=${result.model} voiceIdLength=${voiceId?.length}",
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
        val sysVoice = sysProvider.getVoices().firstOrNull()?.id
        assertTrue("no system voice", sysVoice != null)
        val cloudKey = args("minimaxApiKey")
            ?: return@runBlocking println("E2E 3-way cycle SKIPPED (no -e minimaxApiKey)")
        var cloudVoice = app.voiceProfileManager.observeProfiles().first()
            .firstOrNull { profile -> !profile.minimaxVoiceId.isNullOrBlank() }
            ?.minimaxVoiceId
        if (cloudVoice == null) {
            // No profile carries a cloud binding: clone once from the on-device
            // E2E reference (>=10 s) and persist the binding for later runs.
            val cloneRef = File(
                args("cloneRef")
                    ?: "/sdcard/Android/data/com.shinevoice.debug/files/voices/minimax-e2e/reference.wav",
            )
            assertTrue("no bound cloud voice and no cloneRef at ${cloneRef.path}", cloneRef.isFile)
            val clone = (app.providerRegistry.get(MiniMaxProvider.PROVIDER_ID) as MiniMaxProvider).cloneVoice(
                com.shinevoice.domain.tts.VoiceCloneRequest(
                    voiceProfileId = "e2e-clone",
                    referenceAudioPath = cloneRef.absolutePath,
                    referenceText = "",
                ),
            )
            assertTrue("cloud clone failed: ${clone.error?.userMessage}", clone.success)
            cloudVoice = clone.voiceId
            app.voiceProfileManager.observeProfiles().first().firstOrNull()?.let { profile ->
                app.voiceProfileManager.updateCloudBinding(profile.id, cloudVoice!!)
            }
            println("E2E 3-way cloned fresh cloud voice (idLength=${cloudVoice?.length})")
        }
        app.minimaxConfig.save("", cloudKey, com.shinevoice.data.settings.MiniMaxRegion.CN)
        assertTrue(
            "cloud pre-flight failed",
            (app.providerRegistry.get(MiniMaxProvider.PROVIDER_ID) as MiniMaxProvider)
                .validateConfig().success,
        )

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
                val voiceOk = result.voiceId == null || voiceId == null || result.voiceId == voiceId
                val wavOk = result.success && isWav(File(result.audioFile!!))
                if (!result.success || !providerOk || !voiceOk || !wavOk) {
                    failures++
                    println(
                        "E2E 3-way FAILURE round=${round + 1} label=$label success=${result.success} " +
                            "providerEcho=${result.providerId} voiceEcho=${result.voiceId} " +
                            "err=${result.error?.userMessage}",
                    )
                }
                log.append(
                    "round=${round + 1} want=$label($providerId) got=${result.providerId} " +
                        "voice=${result.voiceId} ok=${result.success} ${result.elapsedMs}ms " +
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

    /** A wrong API key must surface a Chinese business error, never a raw stack. */
    @Test
    fun test05_minimaxWrongKeyRejected() = runBlocking {
        val provider = app.providerRegistry.get(MiniMaxProvider.PROVIDER_ID) as MiniMaxProvider
        app.minimaxConfig.save("", "sk-invalid-key-e2e-test-000000", com.shinevoice.data.settings.MiniMaxRegion.CN)
        val probe = provider.validateConfig()
        assertTrue("wrong key should fail", !probe.success)
        val message = probe.error?.userMessage.orEmpty()
        println("E2E MiniMax wrong-key message: $message")
        assertTrue(
            "error should be a business message, got: $message",
            message.contains("Key") || message.contains("无效") || message.contains("权限") || message.contains("鉴权"),
        )
        assertTrue("must not leak the key itself", !message.contains("sk-invalid"))
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
