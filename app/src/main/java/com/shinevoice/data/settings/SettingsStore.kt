package com.shinevoice.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.shinevoice.core.audio.PlaybackRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.shineVoiceDataStore by preferencesDataStore(name = "shinevoice_settings")

enum class ThemeMode(val storedName: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        // Fresh installs default to the dark cyberpunk theme; an explicit
        // user choice (system/light) is always preserved once stored.
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.storedName == name } ?: DARK
    }
}

class SettingsStore(private val context: Context) {
    private val autoSaveKey = booleanPreferencesKey("auto_save_generated_audio")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val systemTtsEngineKey = stringPreferencesKey("system_tts_engine")
    private val systemTtsVoiceKey = stringPreferencesKey("system_tts_voice")
    private val activeLocalModelKey = stringPreferencesKey("active_local_model")
    private val playbackRouteKey = stringPreferencesKey("playback_route")
    private val generationLanguageKey = stringPreferencesKey("generation_language")
    private val speechRateKey = floatPreferencesKey("speech_rate")
    private val updateLastCheckAtKey = longPreferencesKey("update_last_check_at")

    val autoSave: Flow<Boolean> = context.shineVoiceDataStore.data.map { preferences ->
        preferences[autoSaveKey] ?: false
    }

    val themeMode: Flow<ThemeMode> = context.shineVoiceDataStore.data.map { preferences ->
        ThemeMode.fromName(preferences[themeModeKey])
    }

    /** Selected Android System TTS engine package; null = system default engine. */
    val systemTtsEngine: Flow<String?> = context.shineVoiceDataStore.data.map { preferences ->
        preferences[systemTtsEngineKey]?.takeIf { it.isNotBlank() }
    }

    val systemTtsVoice: Flow<String?> = context.shineVoiceDataStore.data.map { preferences ->
        preferences[systemTtsVoiceKey]?.takeIf { it.isNotBlank() }
    }

    suspend fun setAutoSave(enabled: Boolean) {
        context.shineVoiceDataStore.edit { preferences ->
            preferences[autoSaveKey] = enabled
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.shineVoiceDataStore.edit { preferences ->
            preferences[themeModeKey] = mode.storedName
        }
    }

    suspend fun setSystemTtsEngine(enginePackage: String?) {
        context.shineVoiceDataStore.edit { preferences ->
            if (enginePackage.isNullOrBlank()) preferences.remove(systemTtsEngineKey)
            else preferences[systemTtsEngineKey] = enginePackage
        }
    }

    suspend fun setSystemTtsVoice(voiceName: String?) {
        context.shineVoiceDataStore.edit { preferences ->
            if (voiceName.isNullOrBlank()) preferences.remove(systemTtsVoiceKey)
            else preferences[systemTtsVoiceKey] = voiceName
        }
    }

    /** Selected playback output route; default SPEAKER. */
    val playbackRoute: Flow<PlaybackRoute> = context.shineVoiceDataStore.data.map { preferences ->
        PlaybackRoute.fromName(preferences[playbackRouteKey])
    }

    /** Last selected output language, persisted independently of the provider. */
    val generationLanguage: Flow<String> = context.shineVoiceDataStore.data.map { preferences ->
        preferences[generationLanguageKey] ?: "zh-CN"
    }

    /** Last selected speech rate; clamped at the state boundary as well. */
    val speechRate: Flow<Float> = context.shineVoiceDataStore.data.map { preferences ->
        (preferences[speechRateKey] ?: 1.0f).coerceIn(0.5f, 2.0f)
    }

    /** Timestamp used only for the automatic 24-hour update-check throttle. */
    val updateLastCheckAt: Flow<Long> = context.shineVoiceDataStore.data.map { preferences ->
        preferences[updateLastCheckAtKey] ?: 0L
    }

    suspend fun setPlaybackRoute(route: PlaybackRoute) {
        context.shineVoiceDataStore.edit { preferences ->
            preferences[playbackRouteKey] = route.storedName
        }
    }

    suspend fun setGenerationLanguage(languageId: String) {
        context.shineVoiceDataStore.edit { preferences ->
            preferences[generationLanguageKey] = languageId
        }
    }

    suspend fun setSpeechRate(rate: Float) {
        context.shineVoiceDataStore.edit { preferences ->
            preferences[speechRateKey] = rate.coerceIn(0.5f, 2.0f)
        }
    }

    suspend fun setUpdateLastCheckAt(timestampMs: Long) {
        context.shineVoiceDataStore.edit { preferences ->
            preferences[updateLastCheckAtKey] = timestampMs
        }
    }

    /** Active local model id (LocalModelRegistry); null = catalog default. */
    val activeLocalModel: Flow<String?> = context.shineVoiceDataStore.data.map { preferences ->
        preferences[activeLocalModelKey]?.takeIf { it.isNotBlank() }
    }

    suspend fun setActiveLocalModel(modelId: String) {
        context.shineVoiceDataStore.edit { preferences ->
            preferences[activeLocalModelKey] = modelId
        }
    }
}
