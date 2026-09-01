package com.shinevoice

import android.app.Application
import androidx.room.Room
import com.shinevoice.core.audio.ReferenceAudioImporter
import com.shinevoice.core.log.AppLogger
import com.shinevoice.core.storage.ModelDirectoryResolver
import com.shinevoice.core.storage.ReferenceAudioLoader
import com.shinevoice.core.storage.WavStorage
import com.shinevoice.data.db.ShineVoiceDatabase
import com.shinevoice.data.repository.RoomGenerationHistoryRepository
import com.shinevoice.data.settings.MiniMaxConfig
import com.shinevoice.data.settings.SettingsStore
import com.shinevoice.domain.tts.ProviderRegistry
import com.shinevoice.domain.tts.TtsManager
import com.shinevoice.domain.voice.VoiceProfileManager
import com.shinevoice.provider.androidtts.AndroidSystemTtsProvider
import com.shinevoice.provider.minimax.MiniMaxApiClient
import com.shinevoice.provider.minimax.MiniMaxProvider
import com.shinevoice.provider.sherpa.SherpaRuntimeManager
import com.shinevoice.provider.sherpa.SherpaZipVoiceProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ShineVoiceApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Transient hand-off of a freshly built ZIP before the SAF save dialog completes. */
    @Volatile var zippedExport: java.io.File? = null
    val logger by lazy { AppLogger() }
    val modelResolver by lazy { ModelDirectoryResolver(this) }
    val wavStorage by lazy { WavStorage(this) }
    val settingsStore by lazy { SettingsStore(this) }
    val minimaxConfig by lazy { MiniMaxConfig(this) }
    val minimaxApiClient by lazy { MiniMaxApiClient() }
    val audioImporter by lazy { ReferenceAudioImporter(this) }
    val database by lazy {
        Room.databaseBuilder(this, ShineVoiceDatabase::class.java, "shinevoice.db")
            .addMigrations(ShineVoiceDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
    val historyRepository by lazy { RoomGenerationHistoryRepository(database.generationHistoryDao()) }
    val voiceProfileManager by lazy { VoiceProfileManager(database.voiceProfileDao(), this) }
    val providerRegistry by lazy { ProviderRegistry(logger) }
    val sherpaRuntime by lazy {
        SherpaRuntimeManager(modelResolver, ReferenceAudioLoader(), logger)
    }
    val ttsManager by lazy {
        val zipVoice = SherpaZipVoiceProvider(modelResolver, sherpaRuntime, wavStorage, logger)
        providerRegistry.register(zipVoice)
        val systemTts = AndroidSystemTtsProvider(this, wavStorage, logger)
        providerRegistry.register(systemTts)
        val minimax = MiniMaxProvider(minimaxConfig, minimaxApiClient, wavStorage, logger)
        providerRegistry.register(minimax)
        TtsManager(providerRegistry, historyRepository, logger)
    }

    override fun onCreate() {
        super.onCreate()
        // Registration is lazy so process start never loads a multi-hundred-MB
        // model. MainViewModel initializes Native only after the model check.
        ttsManager
        applicationScope.launch {
            runCatching { voiceProfileManager.ensureDefaultProfile() }
                .onFailure { logger.w("Could not seed default voice profile", it) }
        }
        logger.i("ShineVoice application started")
    }
}