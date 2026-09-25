package com.shinevoice.feature.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.shinevoice.MainActivity
import com.shinevoice.R
import com.shinevoice.core.playback.PlaybackService
import com.shinevoice.data.db.GenerationHistoryEntity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * User-initiated cross-app control session: keeps the floating button visible
 * while the user works in other apps (e.g. preparing to hold WeChat's talk
 * button). This is NOT a media keep-alive: no audio plays here, the FGS type
 * is specialUse (API 34+) with an explicit subtype description, and closing
 * the session stops both the overlay and any pending countdown.
 *
 * Lifecycle rules:
 *  - Started only from a visible Activity via [Companion.start] after the
 *    overlay permission is granted.
 *  - Screen-off cancels the countdown and hides the window; screen-on
 *    re-adds the button (session continues).
 *  - Permission revoked / window rejected / close button → stopSelf cleanly.
 */
class OverlayControllerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowController: OverlayWindowController
    private lateinit var delayedPlayback: DelayedPlaybackController

    private var selectedClip: OverlayWindowController.OverlayClip? = null
    private var recentClips: List<OverlayWindowController.OverlayClip> = emptyList()
    private var delaySeconds = 3
    private var panelVisible = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Lock screen: cancel any pending assist countdown.
                    delayedPlayback.cancel(fired = false)
                    windowController.showCountdown(-1)
                    windowController.remove()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    if (!hasOverlayPermission()) {
                        stopSelf()
                        return
                    }
                    launchWithPosition { x, y -> windowController.addButton(x, y) }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val app = application as com.shinevoice.ShineVoiceApplication
        windowController = OverlayWindowController(this, callbacks)
        delayedPlayback = DelayedPlaybackController(
            onTick = { remaining ->
                windowController.showCountdown(remaining)
                if (panelVisible) windowController.hidePanel()
            },
            onFire = { firePlayback() },
            onEnd = { fired -> if (!fired) windowController.showCountdown(-1) },
        )
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
        startInForeground()

        scope.launch {
            app.settingsStore.playbackRoute.collectLatest { /* read-through; PlaybackService applies */ }
        }
        scope.launch {
            app.overlaySettings.defaultDelaySeconds.collectLatest { seconds ->
                delaySeconds = seconds
                renderPanel()
            }
        }
        scope.launch { observeHistory(app) }
        scope.launch {
            PlaybackService.Hub.state.collectLatest { state ->
                windowController.setPlaying(state.isPlaying)
                renderPanel()
            }
        }
    }

    private suspend fun observeHistory(app: com.shinevoice.ShineVoiceApplication) {
        app.historyRepository.observeAll().collectLatest { history ->
            val clips = history
                .filter { it.success && !it.audioPath.isNullOrBlank() && File(it.audioPath).isFile }
                .take(RECENT_LIMIT)
                .map { it.toOverlayClip() }
            val ids = clips.map { it.id }.toSet()
            if (selectedClip?.id !in ids) {
                selectedClip = clips.firstOrNull()
            }
            recentClips = clips
            renderPanel()
        }
    }

    private fun GenerationHistoryEntity.toOverlayClip(): OverlayWindowController.OverlayClip {
        val label = inputText.take(18).ifBlank { "生成音频" }
        return OverlayWindowController.OverlayClip(
            id = taskId,
            title = "$label · ${language ?: ""}",
            filePath = audioPath!!,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SELECT_CLIP -> {
                val id = intent.getStringExtra(EXTRA_CLIP_ID) ?: return START_NOT_STICKY
                selectedClip = recentClips.firstOrNull { it.id == id } ?: selectedClip
                renderPanel()
            }
            ACTION_CLOSE -> stopSelf()
            else -> {
                if (!hasOverlayPermission()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                launchWithPosition { x, y -> windowController.addButton(x, y) }
            }
        }
        return START_NOT_STICKY
    }

    private fun launchWithPosition(block: (Float, Float) -> Unit) {
        scope.launch {
            val settings = (application as com.shinevoice.ShineVoiceApplication).overlaySettings
            val x = settings.posXRatio.first()
            val y = settings.posYRatio.first()
            block(x, y)
        }
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun renderPanel() {
        if (panelVisible || windowControllerHasPanel()) {
            windowController.renderPanelContent(
                items = recentClips,
                playingId = PlaybackService.Hub.currentState.itemId.takeIf { PlaybackService.Hub.currentState.isPlaying },
                selectedId = selectedClip?.id,
                delaySeconds = delaySeconds,
            )
        }
    }

    private fun windowControllerHasPanel(): Boolean = panelVisible

    private val callbacks = object : OverlayWindowController.Callbacks {        override fun onPlayRequested() {
            // Immediate playback button (no countdown).
            val clip = selectedClip ?: return
            windowController.hidePanel()
            PlaybackService.Hub.play(this@OverlayControllerService, clip.id, clip.filePath, clip.title)
        }

        override fun onStopRequested() {
            PlaybackService.Hub.stop(this@OverlayControllerService)
        }

        override fun onClipPicked(item: OverlayWindowController.OverlayClip) {
            // Selecting a clip only highlights it; the countdown is armed by
            // the explicit ⏱ 倒计时 button.
            selectedClip = item
            renderPanel()
        }

        override fun onDelaySelected(seconds: Int) {
            delaySeconds = seconds
            scope.launch {
                (application as com.shinevoice.ShineVoiceApplication)
                    .overlaySettings.saveDefaultDelay(seconds)
            }
            renderPanel()
        }

        override fun onCountdownPlayRequested() {
            armCountdown()
        }

        override fun onCountdownTap() {
            // Tapping the button during a countdown cancels it.
            delayedPlayback.cancel(fired = false)
            windowController.showCountdown(-1)
        }

        override fun onCountdownStarted(seconds: Int) = Unit

        override fun onPanelVisibilityChanged(visible: Boolean) {
            panelVisible = visible
            if (visible) renderPanel()
        }

        override fun onPositionChanged(xRatio: Float, yRatio: Float) {
            scope.launch {
                (application as com.shinevoice.ShineVoiceApplication)
                    .overlaySettings.savePosition(xRatio, yRatio)
            }
        }

        override fun onClosed() {
            stopSelf()
        }
    }

    /**
     * The core WeChat-assist interaction: a countdown-armed play. The panel
     * hides, the button counts down, the user switches to WeChat and holds
     * the talk button; when the countdown fires the clip plays on the
     * speaker. Tapping the button again cancels.
     */
    fun armCountdown() {
        val clip = selectedClip ?: return
        windowController.hidePanel()
        delayedPlayback.schedule(delaySeconds)
    }

    private fun firePlayback() {
        val clip = selectedClip ?: return
        windowController.showCountdown(-1)
        PlaybackService.Hub.play(this, clip.id, clip.filePath, clip.title)
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "悬浮播放会话",
                    NotificationManager.IMPORTANCE_MIN,
                ).apply { description = "桌面悬浮播放按钮会话（点按可返回应用关闭）" },
            )
        }
        val content = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val close = PendingIntent.getService(
            this,
            11,
            Intent(this, OverlayControllerService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_playback)
            .setContentTitle("悬浮播放已开启")
            .setContentText("点按钮展开：选择音频 → 倒计时 → 去微信按住说话")
            .setContentIntent(content)
            .setOngoing(true)
            .addAction(0, "关闭悬浮", close)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // specialUse with an explicit subtype: this service renders a
            // user-initiated cross-app control window; it never plays audio
            // and must not masquerade as mediaPlayback.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            // No FGS type on 29-33: nothing real is being played here.
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { unregisterReceiver(screenReceiver) }
        delayedPlayback.cancel(fired = false)
        windowController.remove()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "shinevoice_overlay"
        private const val NOTIFICATION_ID = 42
        const val ACTION_CLOSE = "com.shinevoice.overlay.CLOSE"
        const val ACTION_SELECT_CLIP = "com.shinevoice.overlay.SELECT_CLIP"
        const val EXTRA_CLIP_ID = "clip_id"
        const val RECENT_LIMIT = 5

        /** Same-process liveness flag for UI toggles; not meaningful across processes. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Starts the overlay session; call only with overlay permission granted. */
        fun start(context: Context) {
            val intent = Intent(context, OverlayControllerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OverlayControllerService::class.java).setAction(ACTION_CLOSE),
            )
        }
    }
}
