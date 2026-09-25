package com.shinevoice.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.overlayDataStore by preferencesDataStore(name = "shinevoice_overlay")

/**
 * Overlay button preferences: persisted as screen-relative ratios (survives
 * rotation and density changes), clamped into [0,1] on read.
 */
class OverlaySettings(private val context: Context) {
    private val posXKey = floatPreferencesKey("button_pos_x_ratio")
    private val posYKey = floatPreferencesKey("button_pos_y_ratio")
    private val delayKey = intPreferencesKey("default_delay_seconds")

    val posXRatio: Flow<Float> = context.overlayDataStore.data.map {
        (it[posXKey] ?: 0.95f).coerceIn(0f, 1f)
    }

    val posYRatio: Flow<Float> = context.overlayDataStore.data.map {
        (it[posYKey] ?: 0.55f).coerceIn(0f, 1f)
    }

    val defaultDelaySeconds: Flow<Int> = context.overlayDataStore.data.map {
        (it[delayKey] ?: 3).coerceIn(0, 30)
    }

    suspend fun savePosition(xRatio: Float, yRatio: Float) {
        context.overlayDataStore.edit { prefs ->
            prefs[posXKey] = xRatio.coerceIn(0f, 1f)
            prefs[posYKey] = yRatio.coerceIn(0f, 1f)
        }
    }

    suspend fun saveDefaultDelay(seconds: Int) {
        context.overlayDataStore.edit { prefs ->
            prefs[delayKey] = seconds.coerceIn(0, 30)
        }
    }
}
