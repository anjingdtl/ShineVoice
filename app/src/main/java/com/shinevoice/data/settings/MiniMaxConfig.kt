package com.shinevoice.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.shinevoice.core.security.SecretCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

private val Context.minimaxDataStore by preferencesDataStore(name = "shinevoice_minimax")

/**
 * BYOK MiniMax configuration. The API key is encrypted with Android Keystore
 * (see SecretCipher) before it is persisted; it is never written to logs and
 * never committed to source control.
 */
class MiniMaxConfig(private val context: Context) {
    private val groupIdKey = stringPreferencesKey("group_id")
    private val apiKeyEncKey = stringPreferencesKey("api_key_enc")
    private val voiceIdKey = stringPreferencesKey("default_voice_id")

    val groupId: Flow<String?> = context.minimaxDataStore.data.map { it[groupIdKey] }

    /** Decrypts the stored key on demand; returns null when not configured. */
    suspend fun apiKey(): String? {
        val stored = context.minimaxDataStore.data.first()[apiKeyEncKey] ?: return null
        return runCatching { SecretCipher.decrypt(context, stored) }.getOrNull()
    }

    val defaultVoiceId: Flow<String?> = context.minimaxDataStore.data.map { it[voiceIdKey] }

    suspend fun save(groupId: String, apiKey: String) {
        val encrypted = SecretCipher.encrypt(context, apiKey)
        context.minimaxDataStore.edit { prefs ->
            prefs[groupIdKey] = groupId.trim()
            prefs[apiKeyEncKey] = encrypted
        }
    }

    suspend fun saveGroupId(groupId: String) {
        context.minimaxDataStore.edit { prefs -> prefs[groupIdKey] = groupId.trim() }
    }

    suspend fun saveDefaultVoiceId(voiceId: String) {
        context.minimaxDataStore.edit { prefs -> prefs[voiceIdKey] = voiceId }
    }

    suspend fun clear() {
        context.minimaxDataStore.edit { it.clear() }
    }
}