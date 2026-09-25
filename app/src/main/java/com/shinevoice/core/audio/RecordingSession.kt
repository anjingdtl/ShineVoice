package com.shinevoice.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Outcome of one recording session; carries a human-readable failure reason. */
sealed interface RecordingResult {
    /** PCM16 mono WAV at [file]; [durationMs] is the actual recorded length. */
    data class Success(val file: File, val durationMs: Long, val sampleRate: Int) : RecordingResult
    data class Failure(val reason: String) : RecordingResult
    data object Cancelled : RecordingResult
}

/** Recommended and hard limits for reference recordings (product guidance). */
object RecordingLimits {
    const val RECOMMENDED_MIN_MS = 5_000L
    const val RECOMMENDED_MAX_MS = 20_000L
    const val HARD_MAX_MS = 60_000L
}

/**
 * One microphone capture session writing streaming PCM16 into a unique
 * temporary file. Lifecycle contract (the rewrite of the old VoiceRecorder):
 *
 *  - start()/stop() never touch UI-thread joins; stop() is a suspend that
 *    waits for the worker to finish (bounded).
 *  - The recording worker is the SINGLE owner of the AudioRecord; release
 *    happens exactly once on the worker thread. The old double-release race
 *    (caller + worker) is gone.
 *  - A session's success is judged only from THIS session's temp file; a
 *    failed session can never report success via a leftover old file.
 *  - Hard 60 s cap auto-stops the worker; buffer reads with negative return
 *    values abort into Failure instead of spinning.
 *  - The output is the RAW capture at the device-supported rate (48k/44.1k
 *    probing with fallback); normalization to 24 kHz reference format is done
 *    by [AudioNormalizer] so record and import share one pipeline.
 */
@SuppressLint("MissingPermission")
class RecordingSession(private val outputRawFile: File) {
    private val stopping = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private var worker: Thread? = null
    private var completion: CompletableDeferred<RecordingResult>? = null
    @Volatile private var lastError: String? = null

    val isRecording: Boolean get() = started.get() && !stopping.get()

    fun lastError(): String? = lastError

    /** Opens the mic and starts streaming; returns false when capture can't start. */
    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return false
        lastError = null
        val probed = probeConfiguration()
        if (probed == null) {
            started.set(false)
            lastError = "设备不支持单声道 PCM16 录音（已尝试 48k/44.1k/16k）。"
            return false
        }
        val (record, sampleRate, minBuffer) = probed
        outputRawFile.parentFile?.mkdirs()
        val tempPcm = File(outputRawFile.parentFile, outputRawFile.name + ".session-${System.nanoTime()}.pcm")
        val deferred = CompletableDeferred<RecordingResult>()
        completion = deferred
        worker = thread(name = "shinevoice-record") {
            var result: RecordingResult
            try {
                record.startRecording()
                result = captureTo(tempPcm, record, minBuffer, sampleRate)
            } catch (t: Throwable) {
                result = RecordingResult.Failure("录音出错：${t.message ?: t.javaClass.simpleName}")
            } finally {
                runCatching { record.stop() }
                record.release() // single owner: only this thread releases
            }
            result = finishCapture(result, tempPcm, sampleRate)
            deferred.complete(result)
        }
        return true
    }

    /**
     * Stops the session and waits (bounded) for the worker to flush and write
     * the raw WAV. The caller decides what to do with [RecordingResult].
     */
    suspend fun stop(awaitMs: Long = 4_000L): RecordingResult? {
        if (!started.get()) return null
        stopping.set(true)
        val deferred = completion ?: return null
        val result = withTimeoutOrNull(awaitMs) { deferred.await() }
        if (result == null) {
            // The worker is wedged past the grace period; mark failed and let
            // its finally-block clean up. Never join on the caller thread.
            lastError = "录音停止超时"
        }
        return result
    }

    private fun captureTo(
        tempPcm: File,
        record: AudioRecord,
        minBuffer: Int,
        sampleRate: Int,
    ): RecordingResult {
        val buffer = ShortArray(minBuffer / 2)
        var totalSamples = 0L
        var consecutiveEmpty = 0
        RandomAccessFile(tempPcm, "rw").use { raf ->
            raf.setLength(0)
            val bb = ByteBuffer.allocate(buffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            while (!stopping.get()) {
                val read = record.read(buffer, 0, buffer.size)
                when {
                    read > 0 -> {
                        consecutiveEmpty = 0
                        bb.clear()
                        bb.asShortBuffer().put(buffer, 0, read)
                        raf.write(bb.array(), 0, read * 2)
                        totalSamples += read
                        if (totalSamples * 1000L / sampleRate >= RecordingLimits.HARD_MAX_MS) {
                            // Hard cap: stop as if the user pressed stop.
                            stopping.set(true)
                        }
                    }
                    read == 0 -> {
                        if (++consecutiveEmpty > 50) {
                            return RecordingResult.Failure("麦克风没有返回音频数据，请重试。")
                        }
                    }
                    else -> {
                        return if (totalSamples == 0L) {
                            RecordingResult.Failure("录音启动失败（错误码 $read），请检查麦克风权限。")
                        } else {
                            RecordingResult.Failure("录音被系统中断（错误码 $read）。")
                        }
                    }
                }
            }
        }
        return if (totalSamples == 0L) {
            RecordingResult.Failure("录音太短，未捕获到音频。")
        } else {
            RecordingResult.Success(tempPcm, totalSamples * 1000L / sampleRate, sampleRate)
        }
    }

    /** Turns the raw PCM into the final raw WAV at the captured rate. */
    private fun finishCapture(result: RecordingResult, tempPcm: File, sampleRate: Int): RecordingResult {
        when (result) {
            is RecordingResult.Success -> {
                val samples = readPcm16(tempPcm)
                tempPcm.delete()
                if (samples == null || samples.isEmpty()) {
                    return RecordingResult.Failure("录音数据读取失败。")
                }
                return if (MonoWavWriter.write(outputRawFile, samples, sampleRate)) {
                    RecordingResult.Success(
                        outputRawFile,
                        samples.size.toLong() * 1000L / sampleRate,
                        sampleRate,
                    )
                } else {
                    RecordingResult.Failure("录音文件写入失败。")
                }
            }
            else -> {
                tempPcm.delete()
                (result as? RecordingResult.Failure)?.let { lastError = it.reason }
                return result
            }
        }
    }

    private fun readPcm16(file: File): ShortArray? = runCatching {
        val bytes = file.readBytes()
        if (bytes.size % 2 != 0) return null
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).run {
            val out = ShortArray(bytes.size / 2)
            asShortBuffer().get(out)
            out
        }
    }.getOrNull()

    private fun probeConfiguration(): Triple<AudioRecord, Int, Int>? {
        for (rate in intArrayOf(48_000, 44_100, 16_000)) {
            val minBuffer = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) continue
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer * 2,
                )
            } catch (_: Exception) {
                null
            } ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                return Triple(record, rate, minBuffer)
            }
            record.release()
        }
        return null
    }
}
