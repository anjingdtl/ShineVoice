package com.shinevoice.domain.tts

import com.shinevoice.core.log.AppLogger
import java.util.concurrent.ConcurrentHashMap

enum class ProviderLifecycleState {
    Registered,
    Initialized,
    Failed,
    Released,
}

data class ProviderSnapshot(
    val id: String,
    val displayName: String,
    val state: ProviderLifecycleState,
    val lastError: TtsError? = null,
)

/** The only place where application providers are registered and looked up. */
class ProviderRegistry(private val logger: AppLogger) {
    private val providers = ConcurrentHashMap<String, TtsProvider>()
    private val states = ConcurrentHashMap<String, ProviderSnapshot>()

    fun register(provider: TtsProvider) {
        check(providers.putIfAbsent(provider.id, provider) == null) {
            "Provider already registered: ${provider.id}"
        }
        states[provider.id] = ProviderSnapshot(
            id = provider.id,
            displayName = provider.displayName,
            state = ProviderLifecycleState.Registered,
        )
        logger.i("Provider registered: ${provider.id}")
    }

    fun get(providerId: String): TtsProvider? = providers[providerId]

    fun require(providerId: String): TtsProvider = providers[providerId]
        ?: error("Provider not found: $providerId")

    fun snapshots(): List<ProviderSnapshot> = states.values.sortedBy { it.id }

    suspend fun initialize(providerId: String): ProviderResult {
        val provider = get(providerId) ?: return ProviderResult.failure(
            TtsError(
                TtsErrorCode.ProviderNotFound,
                "未找到语音 Provider：$providerId",
            ),
        )
        val result = provider.initialize()
        states[providerId] = ProviderSnapshot(
            id = provider.id,
            displayName = provider.displayName,
            state = if (result.success) ProviderLifecycleState.Initialized else ProviderLifecycleState.Failed,
            lastError = result.error,
        )
        return result
    }

    suspend fun releaseAll() {
        providers.values.forEach { provider ->
            runCatching { provider.release() }
                .onFailure { logger.w("Provider release failed: ${provider.id}", it) }
            states[provider.id] = ProviderSnapshot(
                id = provider.id,
                displayName = provider.displayName,
                state = ProviderLifecycleState.Released,
            )
        }
    }
}

