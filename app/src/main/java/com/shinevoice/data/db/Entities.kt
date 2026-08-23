package com.shinevoice.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "voice_profiles")
data class VoiceProfileEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val referenceAudioPath: String?,
    val referenceText: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

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

