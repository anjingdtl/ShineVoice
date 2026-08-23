package com.shinevoice

import com.shinevoice.core.log.AppLogger
import com.shinevoice.core.model.AudioFormat
import com.shinevoice.domain.tts.ProviderRegistry
import com.shinevoice.domain.tts.ProviderResult
import com.shinevoice.domain.tts.TtsCapabilities
import com.shinevoice.domain.tts.TtsProvider
import com.shinevoice.domain.tts.TtsRequest
import com.shinevoice.domain.tts.TtsResult
import com.shinevoice.domain.tts.TtsVoice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRegistryTest {
    @Test
    fun registerInitializeAndSnapshot() = runBlocking {
        val registry = ProviderRegistry(AppLogger("ShineVoiceTest"))
        registry.register(FakeProvider())

        assertEquals("fake", registry.get("fake")?.id)
        assertEquals(ProviderResult.ok("initialized"), registry.initialize("fake"))
        assertEquals(1, registry.snapshots().size)
        assertEquals("Initialized", registry.snapshots().single().state.name)
    }

    @Test
    fun resultCalculatesRtf() {
        val result = TtsResult(
            taskId = "t",
            providerId = "fake",
            success = true,
            durationMs = 2_000,
            elapsedMs = 500,
        )
        assertTrue(result.rtf!! == 0.25)
    }

    private class FakeProvider : TtsProvider {
        override val id = "fake"
        override val displayName = "Fake test provider"
        override suspend fun initialize() = ProviderResult.ok("initialized")
        override suspend fun getCapabilities() = TtsCapabilities(
            supportsVoiceClone = false,
            supportsOffline = true,
            supportsStreaming = false,
            supportsSpeed = false,
            supportsPitch = false,
            supportsEmotion = false,
            supportsFileOutput = false,
            supportedFormats = setOf(AudioFormat.WAV_PCM_16),
        )
        override suspend fun getVoices() = listOf(TtsVoice("fake", "Fake"))
        override suspend fun synthesize(request: TtsRequest) = TtsResult(
            taskId = request.taskId,
            providerId = id,
            success = true,
            elapsedMs = 1,
        )
        override suspend fun cancel(taskId: String) = Unit
        override suspend fun validateConfig() = ProviderResult.ok()
        override suspend fun release() = Unit
    }
}

