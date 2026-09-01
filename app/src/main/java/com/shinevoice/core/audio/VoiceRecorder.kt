package com.shinevoice.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Records a short reference clip via AudioRecord (PCM 16-bit mono), then
 * normalizes it to TARGET_SAMPLE_RATE WAV. Uses a safe 44.1 kHz record rate and
 * resamples down to 24 kHz so it works across devices without exotic sample
 * rate support.
 */
@SuppressLint("MissingPermission")
class VoiceRecorder(private val outputFile: File) {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var record: AudioRecord? = null
    private var error: String? = null
    private var sampleRate = 44_100

    val isRecording: Boolean get() = running.get()

    fun lastError(): String? = error

    fun start(): Boolean {
        if (running.getAndSet(true)) return false
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (bufferSize <= 0) {
            running.set(false)
            error = "设备不支持录音缓冲（$sampleRate Hz）"
            return false
        }
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            running.set(false)
            error = "录音权限未授予或麦克风不可用"
            return false
        }
        this.record = record
        val samples = mutableListOf<Short>()
        worker = thread(name = "shinevoice-record") {
            record.startRecording()
            val buffer = ShortArray(bufferSize / 2)
            while (running.get()) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) synchronized(samples) { samples.addAll(buffer.copyOf(read).toList()) }
            }
            runCatching { record.stop() }
            record.release()
            val pcm = synchronized(samples) { samples.toShortArray() }
            if (pcm.size > 0 && running.get() == false) {
                val normalized = Resampler.toRate(TARGET_SAMPLE_RATE, pcm, sampleRate)
                MonoWavWriter.write(outputFile, normalized, TARGET_SAMPLE_RATE)
            }
            this.record = null
        }
        return true
    }

    /** Stops recording and returns true when the normalized WAV was written. */
    fun stop(): Boolean {
        if (!running.getAndSet(false)) return false
        worker?.join(3_000)
        worker = null
        val current = record
        if (current != null) {
            runCatching { current.stop() }
            current.release()
            record = null
        }
        return outputFile.isFile && outputFile.length() > 44
    }
}