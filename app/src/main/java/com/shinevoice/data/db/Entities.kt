package com.shinevoice.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-managed voice (音色). One profile can bind the same voice across
 * ZipVoice (local), MiniMax (cloud) and Android System TTS.
 */
@Entity(tableName = "voice_profiles")
data class VoiceProfileEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    /** ZipVoice binding: normalized reference audio (voices/{id}/reference.wav). */
    val referenceAudioPath: String?,
    /** ZipVoice binding: transcript matching the reference audio. */
    val referenceText: String?,
    /** Original imported/recorded file kept for re-normalization (voices/{id}/source.*). */
    val sourceAudioPath: String?,
    /** MiniMax binding: remote cloned voice id. */
    val minimaxVoiceId: String?,
    /** Android System TTS binding: engine + voice identifiers. */
    val androidTtsEngine: String?,
    val androidTtsVoice: String?,
    val isCurrent: Boolean = false,
    val isDefault: Boolean = false,
    val lastUsedAt: Long = 0L,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** Availability per provider binding, used for UI badges/状态. */
    val hasLocalBinding: Boolean get() = referenceAudioPath != null || sourceAudioPath != null
    val hasCloudBinding: Boolean get() = !minimaxVoiceId.isNullOrBlank()
    val hasSystemBinding: Boolean get() = !androidTtsVoice.isNullOrBlank()
}

@Entity(tableName = "generation_history")
data class GenerationHistoryEntity(
    @PrimaryKey val taskId: String,
    val inputText: String,
    val providerId: String,
    val voiceProfileId: String?,
    val model: String?,
    val createdAt: Long,
    val elapsedMs: Long,
    val durationMs: Long?,
    val audioPath: String?,
    val success: Boolean,
    val errorCode: String?,
    val errorMessage: String?,
)