package com.shinevoice.domain.tts

import com.shinevoice.core.log.AppLogger
import com.shinevoice.data.repository.GenerationHistoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.system.measureTimeMillis

/** Conservative V0.1 task coordinator: one generation at a time across providers. */
class TtsManager(
    private val registry: ProviderRegistry,
    private val historyRepository: GenerationHistoryRepository,
    private val logger: AppLogger,
) {
    private val taskMutex = Mutex()

    suspend fun initialize(providerId: String): ProviderResult = registry.initialize(providerId)

    suspend fun synthesize(request: TtsRequest): TtsResult = taskMutex.withLock {
        val provider = registry.get(request.providerId)
        if (provider == null) {
            val error = TtsError(
                TtsErrorCode.ProviderNotFound,
                "未找到语音 Provider：${request.providerId}",
            )
            return@withLock TtsResult.failure(request.taskId, request.providerId, 0L, error)
        }
        if (request.text.isBlank()) {
            val error = TtsError(TtsErrorCode.EmptyText, "请输入需要生成的中文文本。")
            return@withLock TtsResult.failure(request.taskId, request.providerId, 0L, error)
        }

        var result = TtsResult.failure(
            taskId = request.taskId,
            providerId = request.providerId,
            elapsedMs = 0L,
            error = TtsError(TtsErrorCode.Unknown, "语音生成尚未完成。"),
        )
        val elapsed = measureTimeMillis {
            result = try {
                provider.synthesize(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                logger.e("Provider synthesize threw: ${provider.id}", throwable)
                TtsResult.failure(
                    taskId = request.taskId,
                    providerId = provider.id,
                    elapsedMs = 0L,
                    error = TtsError(
                        TtsErrorCode.Unknown,
                        "语音生成失败，请查看 Debug 日志。",
                        throwable.message,
                    ),
                )
            }
        }
        // Provider reports its own elapsed time around Native inference. The
        // coordinator log records the outer task boundary as well.
        if (result.elapsedMs == 0L && elapsed > 0L) {
            result = result.copy(elapsedMs = elapsed)
        }
        runCatching { historyRepository.record(request, result) }
            .onFailure { logger.w("Could not persist generation history", it) }
        logger.i(
            "generation task=${request.taskId} provider=${request.providerId} " +
                "success=${result.success} elapsedMs=${result.elapsedMs}",
        )
        result
    }

    suspend fun cancel(taskId: String, providerId: String) {
        registry.get(providerId)?.cancel(taskId)
    }

    suspend fun release() = registry.releaseAll()
}
