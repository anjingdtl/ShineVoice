package com.shinevoice.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.shineVoiceDataStore by preferencesDataStore(name = "shinevoice_settings")

enum class ThemeMode(val storedName: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.storedName == name } ?: SYSTEM
    }
}

class SettingsStore(private val context: Context) {
    private val autoSaveKey = booleanPreferencesKey("auto_save_generated_audio")
    private val themeModeKey = stringPreferencesKey("theme_mode")

    val autoSave: Flow<Boolean> = context.shineVoiceDataStore.data.map { preferences ->
        preferences[autoSaveKey] ?: false
    }

    val themeMode: Flow<ThemeMode> = context.shineVoiceDataStore.data.map { preferences ->
        ThemeMode.fromName(preferences[themeModeKey])
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
}