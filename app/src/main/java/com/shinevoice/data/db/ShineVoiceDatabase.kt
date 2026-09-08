package com.shinevoice.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [VoiceProfileEntity::class, GenerationHistoryEntity::class],
    version = 3,
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

        /** v2 -> v3: retain every generation row and add task parameters. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE generation_history ADD COLUMN language TEXT",
                )
                db.execSQL(
                    "ALTER TABLE generation_history ADD COLUMN speed REAL NOT NULL DEFAULT 1.0",
                )
            }
        }
    }
}
