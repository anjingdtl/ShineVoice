package com.shinevoice.core.audio

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Small, allocation-bounded PCM speed transform used for native-engine
 * compatibility fallbacks. It preserves the sample rate and changes duration
 * by resampling the already generated mono waveform.
 */
object AudioSpeedScaler {
    fun scale(samples: FloatArray, speed: Float): FloatArray {
        require(speed.isFinite() && speed > 0f) { "speed must be finite and positive" }
        if (samples.isEmpty() || speed == 1f) return samples

        val outputSize = ceil(samples.size / speed).toInt().coerceAtLeast(1)
        val output = FloatArray(outputSize)
        val lastIndex = samples.lastIndex
        for (index in output.indices) {
            val sourcePosition = (index * speed).coerceAtMost(lastIndex.toFloat())
            val lower = floor(sourcePosition).toInt()
            val upper = ceil(sourcePosition).toInt().coerceAtMost(lastIndex)
            val fraction = sourcePosition - lower
            output[index] = samples[lower] + (samples[upper] - samples[lower]) * fraction
        }
        return output
    }
}
