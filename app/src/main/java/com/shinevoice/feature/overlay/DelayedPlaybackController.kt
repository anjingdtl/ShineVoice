package com.shinevoice.feature.overlay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * One-shot delayed playback trigger on the monotonic clock. Re-scheduling
 * replaces any pending trigger (only the last one can ever fire); cancelling
 * is idempotent; the callback re-validates its token before firing so a
 * stale scheduled task can never play audio after a cancel.
 */
class DelayedPlaybackController(
    private val onTick: (remainingSeconds: Int) -> Unit,
    private val onFire: () -> Unit,
    private val onEnd: (fired: Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var token: Int = 0
    private var fireAtElapsedMs: Long = 0L
    private var totalSeconds: Int = 0

    val isCounting: Boolean get() = token != 0

    /** Schedules a new countdown, replacing any pending one. */
    fun schedule(delaySeconds: Int) {
        cancel(fired = false)
        if (delaySeconds <= 0) {
            onFire()
            onEnd(true)
            return
        }
        token += 1
        val myToken = token
        totalSeconds = delaySeconds
        fireAtElapsedMs = SystemClock.elapsedRealtime() + delaySeconds * 1_000L
        onTick(delaySeconds)
        handler.post(object : Runnable {
            override fun run() {
                if (myToken != token) return // replaced or cancelled
                val remaining = ((fireAtElapsedMs - SystemClock.elapsedRealtime()) / 1000L).toInt() + 1
                if (remaining > 0) {
                    onTick(remaining.coerceAtMost(totalSeconds))
                    handler.postDelayed(this, 200)
                } else {
                    token = 0
                    onFire()
                    onEnd(true)
                }
            }
        })
    }

    /** Cancels the pending countdown; safe to call repeatedly. */
    fun cancel(fired: Boolean = false) {
        if (token != 0) {
            token = 0
            handler.removeCallbacksAndMessages(null)
            onEnd(fired)
        }
    }
}
