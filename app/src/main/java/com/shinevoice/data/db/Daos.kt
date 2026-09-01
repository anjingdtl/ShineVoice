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

    @Query("SELECT * FROM generation_history ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<GenerationHistoryEntity>>

    @Query("SELECT * FROM generation_history WHERE taskId IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<GenerationHistoryEntity>

    @Query("DELETE FROM generation_history WHERE taskId IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM generation_history")
    suspend fun deleteAll()
}

@Dao
interface VoiceProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: VoiceProfileEntity)

    @Query("SELECT * FROM voice_profiles ORDER BY isCurrent DESC, lastUsedAt DESC")
    fun observeAll(): Flow<List<VoiceProfileEntity>>

    @Query("SELECT * FROM voice_profiles WHERE isCurrent = 1 LIMIT 1")
    fun observeCurrent(): Flow<VoiceProfileEntity?>

    @Query("SELECT * FROM voice_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): VoiceProfileEntity?

    @Query("SELECT * FROM voice_profiles ORDER BY lastUsedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<VoiceProfileEntity>>

    @Query("UPDATE voice_profiles SET isCurrent = 0")
    suspend fun clearCurrent()

    @Query("UPDATE voice_profiles SET isCurrent = 1, lastUsedAt = :now WHERE id = :id")
    suspend fun setCurrent(id: String, now: Long)

    @Query("UPDATE voice_profiles SET lastUsedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("DELETE FROM voice_profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM voice_profiles")
    suspend fun count(): Int
}