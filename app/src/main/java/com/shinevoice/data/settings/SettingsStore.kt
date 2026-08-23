package com.shinevoice.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.shineVoiceDataStore by preferencesDataStore(name = "shinevoice_settings")

class SettingsStore(private val context: Context) {
    private val autoSaveKey = booleanPreferencesKey("auto_save_generated_audio")

    val autoSave: Flow<Boolean> = context.shineVoiceDataStore.data.map { preferences ->
        preferences[autoSaveKey] ?: false
    }

    suspend fun setAutoSave(enabled: Boolean) {
        context.shineVoiceDataStore.edit { preferences ->
            preferences[autoSaveKey] = enabled
        }
    }
}

