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

/** Cloud region keeps the endpoint configurable; no domain is hard-wired forever. */
enum class MiniMaxRegion(val displayName: String, val baseUrl: String) {
    CN("中国大陆", "https://api.minimax.cn"),
    GLOBAL("国际", "https://api.minimax.io");

    companion object {
        fun fromName(name: String?): MiniMaxRegion = entries.firstOrNull { it.name == name } ?: CN

        fun fromBaseUrl(baseUrl: String): MiniMaxRegion =
            entries.firstOrNull { baseUrl.startsWith(it.baseUrl) } ?: CN
    }
}

/**
 * BYOK MiniMax configuration. The API key is encrypted with Android Keystore
 * (see SecretCipher) before it is persisted; it is never written to logs and
 * never committed to source control. GroupId is optional: current sk- keys
 * authenticate purely via the Bearer header, legacy accounts may still need it.
 */
class MiniMaxConfig(private val context: Context) {
    private val groupIdKey = stringPreferencesKey("group_id")
    private val apiKeyEncKey = stringPreferencesKey("api_key_enc")
    private val voiceIdKey = stringPreferencesKey("default_voice_id")
    private val regionKey = stringPreferencesKey("region")

    val groupId: Flow<String?> = context.minimaxDataStore.data.map { it[groupIdKey] }

    val region: Flow<MiniMaxRegion> = context.minimaxDataStore.data.map {
        MiniMaxRegion.fromName(it[regionKey])
    }

    suspend fun baseUrl(): String = region.first().baseUrl

    /** Decrypts the stored key on demand; returns null when not configured. */
    suspend fun apiKey(): String? {
        val stored = context.minimaxDataStore.data.first()[apiKeyEncKey] ?: return null
        return runCatching { SecretCipher.decrypt(context, stored) }.getOrNull()
    }

    val defaultVoiceId: Flow<String?> = context.minimaxDataStore.data.map { it[voiceIdKey] }

    suspend fun save(groupId: String, apiKey: String, region: MiniMaxRegion? = null) {
        val encrypted = SecretCipher.encrypt(context, apiKey)
        context.minimaxDataStore.edit { prefs ->
            prefs[groupIdKey] = groupId.trim()
            prefs[apiKeyEncKey] = encrypted
            region?.let { prefs[regionKey] = it.name }
        }
    }

    suspend fun saveGroupId(groupId: String) {
        context.minimaxDataStore.edit { prefs -> prefs[groupIdKey] = groupId.trim() }
    }

    suspend fun saveRegion(region: MiniMaxRegion) {
        context.minimaxDataStore.edit { prefs -> prefs[regionKey] = region.name }
    }

    suspend fun saveDefaultVoiceId(voiceId: String) {
        context.minimaxDataStore.edit { prefs -> prefs[voiceIdKey] = voiceId }
    }

    suspend fun clear() {
        context.minimaxDataStore.edit { it.clear() }
    }
}
