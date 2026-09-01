package com.shinevoice.core.storage

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.shinevoice.core.audio.AudioRouteManager
import com.shinevoice.core.audio.PlaybackRoute
import java.io.File

/** Activity-scoped playback resource; release it when the Compose host is disposed. */
class AudioPlaybackController(private val routeManager: AudioRouteManager) {
    private var player: MediaPlayer? = null
    private var route: PlaybackRoute = PlaybackRoute.SPEAKER

    /** Invoked on natural playback completion (null until a file is playing). */
    var onCompletion: (() -> Unit)? = null
    var currentFile: File? = null
        private set

    fun play(file: File): Result<Unit> = runCatching {
        require(file.isFile) { "WAV file does not exist" }
        start(file, resumeAtMs = 0)
    }

    /**
     * 切换外放/听筒。空闲时仅更新待用路由；正在播放时从当前位置按新路由续播。
     */
    fun updateRoute(next: PlaybackRoute): Result<Unit> = runCatching {
        if (next == route) return@runCatching
        route = next
        val file = currentFile ?: return@runCatching
        val resumeAtMs = player?.currentPosition ?: 0
        start(file, resumeAtMs)
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

    private fun start(file: File, resumeAtMs: Int) {
        require(file.isFile) { "WAV file does not exist" }
        player?.release()
        player = null
        routeManager.activate(route)
        val nextPlayer = MediaPlayer()
        try {
            nextPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(
                        if (route == PlaybackRoute.EARPIECE) {
                            AudioAttributes.USAGE_VOICE_COMMUNICATION
                        } else {
                            AudioAttributes.USAGE_MEDIA
                        },
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            nextPlayer.setDataSource(file.absolutePath)
            nextPlayer.setOnCompletionListener {
                currentFile = null
                onCompletion?.invoke()
                releaseCurrent()
            }
            nextPlayer.prepare()
            if (resumeAtMs > 0) nextPlayer.seekTo(resumeAtMs)
            nextPlayer.start()
            player = nextPlayer
            currentFile = file
        } catch (throwable: Throwable) {
            nextPlayer.release()
            routeManager.deactivate()
            throw throwable
        }
    }

    private fun releaseCurrent() {
        player?.release()
        player = null
        routeManager.deactivate()
    }
}
