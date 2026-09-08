package com.shinevoice

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.shinevoice.data.db.ShineVoiceDatabase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executes the real 2 -> 3 SQL against a legacy-shaped database. */
class RoomMigrationContractTest {
    @Test
    fun migrationPreservesLegacyRowsAndAddsTaskParameters() {
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .targetContext
        val name = "migration-contract-" + UUID.randomUUID() + ".db"
        val factory = FrameworkSQLiteOpenHelperFactory()
        val oldHelper = factory.create(configuration(context, name, 2, createLegacySchema = true))
        val newHelper = factory.create(configuration(context, name, 3, createLegacySchema = false))

        try {
            val oldDb = oldHelper.writableDatabase
            oldDb.execSQL(
                "INSERT INTO generation_history " +
                    "(taskId, inputText, providerId, voiceProfileId, model, createdAt, elapsedMs, " +
                    "durationMs, audioPath, success, errorCode, errorMessage) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    "legacy-task",
                    "legacy text",
                    "local",
                    null,
                    null,
                    100L,
                    25L,
                    null,
                    null,
                    1,
                    null,
                    null,
                ),
            )
            oldHelper.close()

            val migrated = newHelper.writableDatabase
            val columns = mutableSetOf<String>()
            migrated.query("PRAGMA table_info(generation_history)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            assertTrue("language column missing", "language" in columns)
            assertTrue("speed column missing", "speed" in columns)

            migrated.query(
                "SELECT inputText, language, speed FROM generation_history WHERE taskId = ?",
                arrayOf("legacy-task"),
            ).use { cursor ->
                assertTrue("legacy row missing after migration", cursor.moveToFirst())
                assertEquals("legacy text", cursor.getString(0))
                assertTrue("legacy language must remain null", cursor.isNull(1))
                assertEquals(1.0f, cursor.getFloat(2), 0.0001f)
            }
        } finally {
            oldHelper.close()
            newHelper.close()
            context.deleteDatabase(name)
        }
    }

    private fun configuration(
        context: Context,
        name: String,
        version: Int,
        createLegacySchema: Boolean,
    ): SupportSQLiteOpenHelper.Configuration {
        return SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        if (createLegacySchema) createLegacySchema(db)
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) {
                        if (oldVersion < 3 && newVersion >= 3) {
                            ShineVoiceDatabase.MIGRATION_2_3.migrate(db)
                        }
                    }
                },
            )
            .build()
    }

    private fun createLegacySchema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS generation_history (
                taskId TEXT NOT NULL PRIMARY KEY,
                inputText TEXT NOT NULL,
                providerId TEXT NOT NULL,
                voiceProfileId TEXT,
                model TEXT,
                createdAt INTEGER NOT NULL,
                elapsedMs INTEGER NOT NULL,
                durationMs INTEGER,
                audioPath TEXT,
                success INTEGER NOT NULL,
                errorCode TEXT,
                errorMessage TEXT
            )
            """.trimIndent(),
        )
    }
}
