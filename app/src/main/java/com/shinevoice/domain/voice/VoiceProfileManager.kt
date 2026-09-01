package com.shinevoice.domain.voice

import android.content.Context
import com.shinevoice.core.storage.ModelDirectoryResolver
import com.shinevoice.data.db.VoiceProfileDao
import com.shinevoice.data.db.VoiceProfileEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Application boundary for the user's voice library (音色库). Owns profile rows
 * together with their on-disk reference audio, keeping DB and files consistent.
 */
class VoiceProfileManager(
    private val dao: VoiceProfileDao,
    private val context: Context,
) {
    private val externalRoot = context.getExternalFilesDir(null) ?: context.filesDir
    val voicesRoot: File = File(externalRoot, "voices")

    fun observeProfiles(): Flow<List<VoiceProfileEntity>> = dao.observeAll()

    fun observeCurrent(): Flow<VoiceProfileEntity?> = dao.observeCurrent()

    fun observeRecent(limit: Int = 10): Flow<List<VoiceProfileEntity>> = dao.observeRecent(limit)

    suspend fun getById(id: String): VoiceProfileEntity? = dao.getById(id)

    /** Seeds the built-in default profile pointing at the deployable default reference.wav. */
    suspend fun ensureDefaultProfile() {
        if (dao.count() > 0) return
        val now = System.currentTimeMillis()
        val defaultReference = ModelDirectoryResolver(context).referenceAudio
        val profile = VoiceProfileEntity(
            id = DEFAULT_PROFILE_ID,
            displayName = "默认参考音色",
            referenceAudioPath = defaultReference.absolutePath,
            referenceText = ModelDirectoryResolver.DEFAULT_REFERENCE_TEXT,
            sourceAudioPath = null,
            minimaxVoiceId = null,
            androidTtsEngine = null,
            androidTtsVoice = null,
            isCurrent = true,
            isDefault = true,
            lastUsedAt = now,
            createdAt = now,
            updatedAt = now,
        )
        dao.insert(profile)
    }

    suspend fun create(
        displayName: String,
        referenceText: String?,
        referenceAudioPath: String?,
    ): VoiceProfileEntity {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val profile = VoiceProfileEntity(
            id = id,
            displayName = displayName.trim(),
            referenceAudioPath = referenceAudioPath,
            referenceText = referenceText?.takeIf { it.isNotBlank() },
            sourceAudioPath = null,
            minimaxVoiceId = null,
            androidTtsEngine = null,
            androidTtsVoice = null,
            isCurrent = false,
            isDefault = false,
            lastUsedAt = now,
            createdAt = now,
            updatedAt = now,
        )
        dao.insert(profile)
        return profile
    }

    suspend fun rename(id: String, displayName: String) {
        val existing = dao.getById(id) ?: return
        dao.insert(existing.copy(displayName = displayName.trim(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateReference(
        id: String,
        referenceAudioPath: String? = null,
        referenceText: String? = null,
    ) {
        val existing = dao.getById(id) ?: return
        dao.insert(
            existing.copy(
                referenceAudioPath = referenceAudioPath ?: existing.referenceAudioPath,
                referenceText = referenceText?.takeIf { it.isNotBlank() } ?: existing.referenceText,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun updateCloudBinding(id: String, minimaxVoiceId: String) {
        val existing = dao.getById(id) ?: return
        dao.insert(
            existing.copy(minimaxVoiceId = minimaxVoiceId, updatedAt = System.currentTimeMillis()),
        )
    }

    suspend fun updateSystemBinding(id: String, engine: String?, voiceId: String?) {
        val existing = dao.getById(id) ?: return
        dao.insert(
            existing.copy(
                androidTtsEngine = engine ?: existing.androidTtsEngine,
                androidTtsVoice = voiceId ?: existing.androidTtsVoice,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun setCurrent(id: String) {
        dao.clearCurrent()
        dao.setCurrent(id, System.currentTimeMillis())
    }

    suspend fun touch(id: String) {
        dao.touch(id, System.currentTimeMillis())
    }

    /**
     * Deletes the profile row together with its on-disk voice directory
     * (reference.wav / source.*). The default profile cannot be deleted.
     */
    suspend fun delete(id: String) {
        val profile = dao.getById(id) ?: return
        if (profile.isDefault) return
        profileDir(id).deleteRecursively()
        dao.deleteById(id)
    }

    fun profileDir(id: String): File = File(voicesRoot, id)

    companion object {
        const val DEFAULT_PROFILE_ID = "default-local-reference"
    }
}