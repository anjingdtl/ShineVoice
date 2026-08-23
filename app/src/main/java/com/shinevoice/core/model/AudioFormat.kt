package com.shinevoice.core.model

enum class AudioFormat(
    val extension: String,
    val mimeType: String,
) {
    WAV_PCM_16("wav", "audio/wav"),
}

