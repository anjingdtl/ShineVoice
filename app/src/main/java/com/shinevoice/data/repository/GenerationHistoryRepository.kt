package com.shinevoice.data.repository

import com.shinevoice.data.db.GenerationHistoryDao
import com.shinevoice.data.db.GenerationHistoryEntity
import com.shinevoice.domain.tts.TtsRequest
import com.shinevoice.domain.tts.TtsResult
import kotlinx.coroutines.flow.Flow

interface GenerationHistoryRepository {
    suspend fun record(request: TtsRequest, result: TtsResult)
    fun observeRecent(): Flow<List<GenerationHistoryEntity>>
    fun observeAll(): Flow<List<GenerationHistoryEntity>>
    suspend fun getByIds(ids: List<String>): List<GenerationHistoryEntity>
    suspend fun deleteByIds(ids: List<String>)
}

class RoomGenerationHistoryRepository(
    private val dao: GenerationHistoryDao,
) : GenerationHistoryRepository {
    override suspend fun record(request: TtsRequest, result: TtsResult) {
        dao.insert(
            GenerationHistoryEntity(
                taskId = result.taskId,
                inputText = request.text,
                providerId = result.providerId,
                voiceProfileId = request.voiceProfileId,
                model = result.model,
                createdAt = System.currentTimeMillis(),
                elapsedMs = result.elapsedMs,
                durationMs = result.durationMs,
                audioPath = result.audioFile,
                success = result.success,
                errorCode = result.error?.code?.name,
                errorMessage = result.error?.userMessage,
            ),
        )
    }

    override fun observeRecent(): Flow<List<GenerationHistoryEntity>> = dao.observeRecent()

    override fun observeAll(): Flow<List<GenerationHistoryEntity>> = dao.observeAll()

    override suspend fun getByIds(ids: List<String>): List<GenerationHistoryEntity> = dao.getByIds(ids)

    override suspend fun deleteByIds(ids: List<String>) = dao.deleteByIds(ids)
}

