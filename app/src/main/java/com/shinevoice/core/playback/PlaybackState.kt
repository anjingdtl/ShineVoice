package com.shinevoice.core.playback

/** Snapshot of the shared player owned by PlaybackService. */
data class PlaybackState(
    val itemId: String? = null,
    val title: String? = null,
    val filePath: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val routeName: String? = null,
    /** Human-readable error; safe for UI. */
    val error: String? = null,
)

/** Commands every playback surface (page, notification, overlay) can send. */
sealed interface PlaybackCommand {
    data class Play(val itemId: String, val filePath: String, val title: String) : PlaybackCommand
    data class Stop(val itemId: String? = null) : PlaybackCommand
}
