package com.shinevoice.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [VoiceProfileEntity::class, GenerationHistoryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ShineVoiceDatabase : RoomDatabase() {
    abstract fun generationHistoryDao(): GenerationHistoryDao
    abstract fun voiceProfileDao(): VoiceProfileDao
}

