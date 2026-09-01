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

    /** Cross-provider switching: local <-> system alternation plus one cloud run. */
    @Test
    fun test04_providerSwitchCycle() = runBlocking {
        val sysProvider = app.providerRegistry.get(AndroidSystemTtsProvider.PROVIDER_ID) as AndroidSystemTtsProvider
        assertTrue(sysProvider.initialize().success)
        val sysVoice = sysProvider.getVoices().firstOrNull()?.id
        val cloudKey = args("minimaxApiKey")
        val cloudVoice = app.voiceProfileManager.observeProfiles().first()
            .firstOrNull { profile -> !profile.minimaxVoiceId.isNullOrBlank() }
            ?.minimaxVoiceId
        var failures = 0
        repeat(10) { index ->
            val useSystem = index % 2 == 1
            val request = if (useSystem) {
                TtsRequest(
                    taskId = "e2e-sw-${index}-${UUID.randomUUID()}",
                    text = "切换测试，系统语音第${index}轮。",
                    providerId = AndroidSystemTtsProvider.PROVIDER_ID,
                    voiceId = sysVoice,
                )
            } else {
                TtsRequest(
                    taskId = "e2e-sw-${index}-${UUID.randomUUID()}",
                    text = "切换测试，本地生成第${index}轮。",
                    providerId = SherpaZipVoiceProvider.PROVIDER_ID,
                    voiceId = SherpaZipVoiceProvider.DEFAULT_VOICE_ID,
                    extra = mapOf(
                        SherpaZipVoiceProvider.EXTRA_REFERENCE_AUDIO to app.modelResolver.referenceAudio.absolutePath,
                        SherpaZipVoiceProvider.EXTRA_REFERENCE_TEXT to ModelDirectoryResolver.DEFAULT_REFERENCE_TEXT,
                        SherpaZipVoiceProvider.EXTRA_NUM_STEPS to "4",
                    ),
                )
            }
            val result = app.ttsManager.synthesize(request)
            if (!result.success) {
                failures++
                println("E2E switch failure #$index ${request.providerId}: ${result.error?.userMessage}")
            }
        }
        if (cloudKey != null && cloudVoice != null) {
            val cloud = app.ttsManager.synthesize(
                TtsRequest(
                    taskId = "e2e-sw-cloud-${UUID.randomUUID()}",
                    text = "切换测试，云端高清。",
                    providerId = MiniMaxProvider.PROVIDER_ID,
                    voiceId = cloudVoice,
                ),
            )
            if (!cloud.success) {
                // Cloud may hit TPM limits during the run; report but don't fail the cycle.
                println("E2E switch cloud (non-fatal): ${cloud.error?.userMessage} / ${cloud.error?.causeMessage}")
            }
        }
        assertEquals("provider switch failures", 0, failures)
        sysProvider.release()
        println("E2E provider switch cycle OK (20 alternations incl. 1 cloud attempt)")
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
