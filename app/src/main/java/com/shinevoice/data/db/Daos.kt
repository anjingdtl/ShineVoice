package com.shinevoice.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GenerationHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: GenerationHistoryEntity)

    @Query("SELECT * FROM generation_history ORDER BY createdAt DESC LIMIT 100")
    fun observeRecent(): Flow<List<GenerationHistoryEntity>>
}

@Dao
interface VoiceProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: VoiceProfileEntity)

    @Query("SELECT * FROM voice_profiles ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<VoiceProfileEntity>>
}

