package com.shinevoice.domain.tts

interface TtsProvider {
    val id: String
    val displayName: String

    suspend fun initialize(): ProviderResult
    suspend fun getCapabilities(): TtsCapabilities

    /** Convenience capability query kept at the provider boundary. */
    suspend fun getSupportedLanguages(): List<TtsLanguage> = getCapabilities()
        .supportedLanguages
        .mapNotNull { TtsLanguageCatalog.find(it) }

    suspend fun getVoices(): List<TtsVoice>
    suspend fun synthesize(request: TtsRequest): TtsResult
    suspend fun cancel(taskId: String)
    suspend fun validateConfig(): ProviderResult
    suspend fun release()
}

interface VoiceCloneProvider : TtsProvider {
    suspend fun cloneVoice(request: VoiceCloneRequest): VoiceCloneResult
    suspend fun deleteRemoteVoice(voiceId: String): ProviderResult
}

