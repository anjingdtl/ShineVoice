package com.shinevoice.core.storage

import com.k2fsa.sherpa.onnx.WaveData
import com.k2fsa.sherpa.onnx.WaveReader
import java.io.File

class ReferenceAudioLoader {
    fun load(file: File): WaveData {
        require(file.isFile) { "Reference audio does not exist: ${file.absolutePath}" }
        return WaveReader.readWave(file.absolutePath)
    }
}

