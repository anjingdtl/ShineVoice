package com.shinevoice.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [VoiceProfileEntity::class, GenerationHistoryEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class ShineVoiceDatabase : RoomDatabase() {
    abstract fun generationHistoryDao(): GenerationHistoryDao
    abstract fun voiceProfileDao(): VoiceProfileDao

    companion object {
        /** v1 -> v2: multi-provider binding + current/recent flags on voice_profiles. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE voice_profiles ADD COLUMN sourceAudioPath TEXT",
                )
                db.execSQL(
                    "ALTER TABLE voice_profiles ADD COLUMN minimaxVoiceId TEXT",
                )
                db.execSQL(
                    "ALTER TABLE voice_profiles ADD COLUMN androidTtsEngine TEXT",
                )
                db.execSQL(
                    "ALTER TABLE voice_profiles ADD COLUMN androidTtsVoice TEXT",
                )
                db.execSQL(
                    "ALTER TABLE voice_profiles ADD COLUMN isCurrent INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE voice_profiles ADD COLUMN isDefault INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE voice_profiles ADD COLUMN lastUsedAt INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
    }
}