package com.shinevoice

import android.app.Application
import androidx.room.Room
import com.shinevoice.core.log.AppLogger
import com.shinevoice.core.storage.ModelDirectoryResolver
import com.shinevoice.core.storage.ReferenceAudioLoader
import com.shinevoice.core.storage.WavStorage
import com.shinevoice.data.db.ShineVoiceDatabase
import com.shinevoice.data.repository.RoomGenerationHistoryRepository
import com.shinevoice.data.settings.SettingsStore
import com.shinevoice.domain.tts.ProviderRegistry
import com.shinevoice.domain.tts.TtsManager
import com.shinevoice.provider.sherpa.SherpaRuntimeManager
import com.shinevoice.provider.sherpa.SherpaZipVoiceProvider

class ShineVoiceApplication : Application() {
    val logger by lazy { AppLogger() }
    val modelResolver by lazy { ModelDirectoryResolver(this) }
    val wavStorage by lazy { WavStorage(this) }
    val settingsStore by lazy { SettingsStore(this) }
    val database by lazy {
        Room.databaseBuilder(this, ShineVoiceDatabase::class.java, "shinevoice.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
    val historyRepository by lazy { RoomGenerationHistoryRepository(database.generationHistoryDao()) }
    val providerRegistry by lazy { ProviderRegistry(logger) }
    val sherpaRuntime by lazy {
        SherpaRuntimeManager(modelResolver, ReferenceAudioLoader(), logger)
    }
    val ttsManager by lazy {
        val zipVoice = SherpaZipVoiceProvider(modelResolver, sherpaRuntime, wavStorage, logger)
        providerRegistry.register(zipVoice)
        TtsManager(providerRegistry, historyRepository, logger)
    }

    override fun onCreate() {
        super.onCreate()
        // Registration is lazy so process start never loads a multi-hundred-MB
        // model. MainViewModel initializes Native only after the model check.
        ttsManager
        logger.i("ShineVoice application started")
    }
}
