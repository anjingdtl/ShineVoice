package com.shinevoice.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.shinevoice.core.security.SecretCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.minimaxDataStore by preferencesDataStore(name = "shinevoice_minimax")

/**
 * Cloud region keeps the endpoint configurable; no domain is hard-wired forever.
 * The CN primary follows the current official docs (platform.minimax.cn); the
 * legacy api.minimaxi.com host still serves the same API and keeps resolving
 * for older installs via [fromBaseUrl].
 */
enum class MiniMaxRegion(val displayName: String, val baseUrl: String) {
    CN("中国大陆", "https://api.minimax.cn"),
    GLOBAL("国际", "https://api.minimax.io");

    companion object {
        private val legacyCnHosts = listOf("api.minimaxi.com", "api-bj.minimaxi.com")

        fun fromName(name: String?): MiniMaxRegion = entries.firstOrNull { it.name == name } ?: CN

        fun fromBaseUrl(baseUrl: String): MiniMaxRegion =
            entries.firstOrNull { baseUrl.startsWith(it.baseUrl) }
                // Legacy CN hosts and anything unrecognized stay on the CN region;
                // GLOBAL is only chosen when the stored URL explicitly matches it.
                ?: CN

        /** True when a stored legacy URL still points at the retired CN host. */
        fun isLegacyCnUrl(baseUrl: String): Boolean =
            legacyCnHosts.any { baseUrl.startsWith("https://$it") }
    }
}

/** One atomic view of the stored cloud credentials; a request never mixes revisions. */
data class MiniMaxConfigSnapshot(
    val region: MiniMaxRegion,
    val baseUrl: String,
    val apiKey: String?,
    val groupId: String?,
) {
    val isComplete: Boolean get() = !apiKey.isNullOrBlank()
}

/** Distinguishes "never configured" from "stored key cannot be decrypted on this device". */
sealed interface MiniMaxKeyState {
    data object NotConfigured : MiniMaxKeyState
    data object Undecryptable : MiniMaxKeyState
    data class Available(val key: String) : MiniMaxKeyState
}

/** Pure key-format check shared by the settings UI and the save path. */
object MiniMaxApiKeySanitizer {
    private val zeroWidth = charArrayOf('\u200B', '\u200C', '\u200D', '\uFEFF')

    sealed interface Result {
        data class Valid(val key: String) : Result
        data class Invalid(val reason: String) : Result
    }

    fun sanitize(raw: String): Result {
        // Only leading/trailing whitespace is removed; anything else inside the
        // key is reported, never silently deleted.
        val trimmed = raw.trim()
        return when {
            trimmed.isEmpty() -> Result.Invalid("请填写 API Key。")
            trimmed.any { it in zeroWidth } ->
                Result.Invalid("Key 中含有不可见的零宽字符，请手动重新输入，不要粘贴带格式的文本。")
            trimmed.any { it == '\n' || it == '\r' } ->
                Result.Invalid("Key 中含有换行符，可能粘贴了多行内容，请只保留密钥本身。")
            trimmed.startsWith("Bearer ") || trimmed.startsWith("bearer ") ->
                Result.Invalid("Key 不需要包含 “Bearer ” 前缀，请只填写密钥本身。")
            trimmed.contains(' ') -> Result.Invalid("Key 中含有空格，请检查是否粘贴完整。")
            else -> Result.Valid(trimmed)
        }
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

    /** Reads the whole credential set in one DataStore snapshot (no mixed revisions). */
    suspend fun snapshot(): MiniMaxConfigSnapshot {
        val prefs = context.minimaxDataStore.data.first()
        val region = MiniMaxRegion.fromName(prefs[regionKey])
        val encrypted = prefs[apiKeyEncKey]
        val key = if (encrypted == null) {
            null
        } else {
            runCatching { SecretCipher.decrypt(context, encrypted) }.getOrNull()
        }
        return MiniMaxConfigSnapshot(
            region = region,
            baseUrl = region.baseUrl,
            apiKey = key?.takeIf { it.isNotBlank() },
            groupId = prefs[groupIdKey]?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun baseUrl(): String = snapshot().baseUrl

    /** Decrypts the stored key on demand; null only means "not configured". */
    suspend fun apiKey(): String? = snapshot().apiKey

    /** Like [apiKey] but keeps decryption failures visible to callers. */
    suspend fun keyState(): MiniMaxKeyState {
        val prefs = context.minimaxDataStore.data.first()
        val encrypted = prefs[apiKeyEncKey] ?: return MiniMaxKeyState.NotConfigured
        val key = runCatching { SecretCipher.decrypt(context, encrypted) }.getOrNull()
        return when {
            key == null -> MiniMaxKeyState.Undecryptable
            key.isBlank() -> MiniMaxKeyState.NotConfigured
            else -> MiniMaxKeyState.Available(key)
        }
    }

    val defaultVoiceId: Flow<String?> = context.minimaxDataStore.data.map { it[voiceIdKey] }

    /** Atomically persists region + key + group id; the key is trimmed by the sanitizer first. */
    suspend fun save(groupId: String, apiKey: String, region: MiniMaxRegion? = null) {
        val sanitized = MiniMaxApiKeySanitizer.sanitize(apiKey)
        val key = (sanitized as? MiniMaxApiKeySanitizer.Result.Valid)?.key
            // Historical callers already validated; fall back to a plain trim so
            // save() can never persist hidden whitespace characters silently.
            ?: apiKey.trim()
        val encrypted = SecretCipher.encrypt(context, key)
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
