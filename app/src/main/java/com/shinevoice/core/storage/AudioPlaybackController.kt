package com.shinevoice.core.storage

import android.media.MediaPlayer
import java.io.File

/** Activity-scoped playback resource; release it when the Compose host is disposed. */
class AudioPlaybackController {
    private var player: MediaPlayer? = null

    fun play(file: File): Result<Unit> = runCatching {
        require(file.isFile) { "WAV file does not exist" }
        player?.release()
        player = null
        val nextPlayer = MediaPlayer()
        try {
            nextPlayer.setDataSource(file.absolutePath)
            nextPlayer.setOnCompletionListener { releaseCurrent() }
            nextPlayer.prepare()
            nextPlayer.start()
            player = nextPlayer
        } catch (throwable: Throwable) {
            nextPlayer.release()
            throw throwable
        }
    }

    fun stop() {
        runCatching { player?.stop() }
        releaseCurrent()
    }

    fun release() {
        releaseCurrent()
    }

    private fun releaseCurrent() {
        player?.release()
        player = null
    }
}
