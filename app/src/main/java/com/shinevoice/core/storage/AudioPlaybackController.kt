package com.shinevoice.core.storage

import android.media.MediaPlayer
import java.io.File

/** Activity-scoped playback resource; release it when the Compose host is disposed. */
class AudioPlaybackController {
    private var player: MediaPlayer? = null

    /** Invoked on natural playback completion (null until a file is playing). */
    var onCompletion: (() -> Unit)? = null
    var currentFile: File? = null
        private set

    fun play(file: File): Result<Unit> = runCatching {
        require(file.isFile) { "WAV file does not exist" }
        player?.release()
        player = null
        val nextPlayer = MediaPlayer()
        try {
            nextPlayer.setDataSource(file.absolutePath)
            nextPlayer.setOnCompletionListener {
                currentFile = null
                onCompletion?.invoke()
                releaseCurrent()
            }
            nextPlayer.prepare()
            nextPlayer.start()
            player = nextPlayer
            currentFile = file
        } catch (throwable: Throwable) {
            nextPlayer.release()
            throw throwable
        }
    }

    fun stop() {
        runCatching { player?.stop() }
        currentFile = null
        releaseCurrent()
    }

    fun release() {
        currentFile = null
        releaseCurrent()
    }

    private fun releaseCurrent() {
        player?.release()
        player = null
    }
}