package com.shinevoice.core.audio

import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** Quality report computed while normalizing reference audio. */
data class AudioQualityReport(
    val durationMs: Long,
    val peak: Float,
    val rmsDbfs: Float,
    val dcOffset: Float,
    val clipRatio: Float,
    val nonFiniteSamples: Int,
) {
    val rmsAmplitude: Float get() = 10f.pow(rmsDbfs / 20f)

    /** Actionable warnings in user language; empty when the clip looks fine. */
    val warnings: List<String>
        get() = buildList {
            if (durationMs < RecordingLimits.RECOMMENDED_MIN_MS) {
                add("录音偏短（建议 5 秒以上，当前 ${durationMs / 1000.0.toLong()} 秒左右）")
            }
            if (peak > 0.99f && clipRatio > 0.005f) {
                add("录音存在明显削波（爆音），请离麦克风稍远或调低音量后重录")
            }
            if (rmsDbfs < -45f) {
                add("录音电平过低，请靠近麦克风重录")
            }
            if (rmsDbfs > -6f) {
                add("录音电平过高，容易失真，请降低音量后重录")
            }
        }
}

private fun Float.pow(x: Float): Float = Math.pow(this.toDouble(), x.toDouble()).toFloat()

/**
 * Shared normalization pipeline for BOTH in-app recordings and imported
 * files: decode -> anti-aliased resample -> mono PCM16 24 kHz WAV. Both entry
 * points write to a temporary file and atomically replace the reference, so a
 * failed normalization never destroys the previous valid reference.
 */
object AudioNormalizer {

    data class Output(val referenceFile: File, val report: AudioQualityReport)

    /** Normalizes a decoded [PcmAudio] (any rate, mono) into [referenceFile]. */
    fun normalize(audio: PcmAudio, referenceFile: File): Result<Output> = runCatching {
        val resampled = Resampler.toRate(TARGET_SAMPLE_RATE, audio.samples, audio.sampleRate)
        val report = analyze(resampled)
        if (report.durationMs <= 0) {
            throw IllegalArgumentException("音频为空，无法用作参考音频。")
        }
        if (!MonoWavWriter.write(referenceFile, resampled, TARGET_SAMPLE_RATE)) {
            throw IllegalStateException("参考音频写入失败。")
        }
        Output(referenceFile, report)
    }

    /** Normalizes an existing mono PCM16 WAV (e.g. a fresh RecordingSession raw capture). */
    fun normalizeWav(sourceWav: File, referenceFile: File): Result<Output> = runCatching {
        val audio = MonoWavReader.read(sourceWav)
            ?: throw IllegalArgumentException("录音文件无法解析，请重试。")
        normalize(audio, referenceFile).getOrThrow()
    }

    /** Pure statistics over normalized PCM16 samples; never mutates input. */
    fun analyze(samples: ShortArray, sampleRate: Int = TARGET_SAMPLE_RATE): AudioQualityReport {
        if (samples.isEmpty()) {
            return AudioQualityReport(0L, 0f, -200f, 0f, 0f, 0)
        }
        var peak = 0f
        var sumSquares = 0.0
        var sum = 0.0
        var clip = 0
        for (s in samples) {
            val v = s / 32768f
            val a = abs(v)
            if (a > peak) peak = a
            // Counts both full-scale polarities: 32767/32768 == 0.9999695.
            if (a >= 0.9999f) clip++
            sumSquares += (v * v).toDouble()
            sum += v.toDouble()
        }
        val n = samples.size
        val rms = sqrt(sumSquares / n).toFloat()
        return AudioQualityReport(
            durationMs = n.toLong() * 1000L / sampleRate,
            peak = peak,
            rmsDbfs = if (rms > 0f) (20f * log10(rms)) else -200f,
            dcOffset = (sum / n).toFloat(),
            clipRatio = clip.toFloat() / n,
            nonFiniteSamples = 0, // PCM16 cannot hold non-finite values
        )
    }
}
