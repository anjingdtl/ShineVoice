package com.shinevoice.core.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.shinevoice.MainActivity
import com.shinevoice.R
import com.shinevoice.core.audio.PlaybackRoute
import com.shinevoice.data.settings.SettingsStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The single owner of audio playback. Pages, the system notification, and the
 * overlay window all send [PlaybackCommand]s here; nobody else instantiates a
 * MediaPlayer for generated files. Runs as a foreground service with the
 * real mediaPlayback type ONLY while actually playing — stopping playback
 * stops the foreground lifecycle (no silent-loop keep-alive).
 *
 * Audio policy: USAGE_MEDIA + CONTENT_TYPE_SPEECH, focus via a single
 * AudioFocusRequest. Focus loss / device-route change (headset unplug)
 * stops playback outright — the WeChat assist flow must never auto-resume
 * after an interruption.
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var route: PlaybackRoute = PlaybackRoute.SPEAKER
    private lateinit var audioManager: AudioManager
    private lateinit var settings: SettingsStore

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            // A headset disappearing mid-playback is an interruption risk for
            // the speaker-assist flow: stop rather than suddenly blaring.
            if (Hub.currentState.isPlaying) stopSelfPlaying("播放设备已断开")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Hub.attach(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        settings = (application as com.shinevoice.ShineVoiceApplication).settingsStore
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        scope.launch {
            settings.playbackRoute.collectLatest { next ->
                if (next != route) {
                    route = next
                    // Route change while playing: restart the current item so
                    // the new output takes effect.
                    val state = Hub.currentState
                    if (state.isPlaying && state.filePath != null) {
                        playInternal(state.filePath!!, state.itemId ?: "", state.title ?: "", resumeMs = currentPosition())
                    }
                }
            }
        }
        registerNoisyReceiver()
    }

    private var noisyReceiver: android.content.BroadcastReceiver? = null

    private fun registerNoisyReceiver() {
        noisyReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && Hub.currentState.isPlaying) {
                    stopSelfPlaying("音频输出切换")
                }
            }
        }
        registerReceiver(
            noisyReceiver,
            android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val path = intent.getStringExtra(EXTRA_PATH) ?: return START_NOT_STICKY
                val id = intent.getStringExtra(EXTRA_ID) ?: ""
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                playInternal(path, id, title, resumeMs = 0L)
            }
            ACTION_STOP -> stopSelfPlaying(null)
            else -> {
                // Started without a command (e.g. system restart of a sticky
                // service): nothing to resume — process death must not
                // suddenly make noise.
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun playInternal(path: String, itemId: String, title: String, resumeMs: Long) {
        val file = File(path)
        if (!file.isFile || file.length() <= 44L) {
            Hub.update { it.copy(isPlaying = false, error = "音频文件不存在或已损坏") }
            stopSelf()
            return
        }
        player?.release()
        player = null
        if (!requestFocus()) {
            Hub.update { it.copy(isPlaying = false, error = "无法获得音频焦点（其他应用正在播放）") }
            stopSelf()
            return
        }
        val next = MediaPlayer()
        try {
            next.setAudioAttributes(playbackAttributes())
            next.setDataSource(path)
            next.setOnPreparedListener { p ->
                if (resumeMs in 1 until p.duration) p.seekTo(resumeMs.toInt())
                p.start()
                Hub.update {
                    it.copy(
                        itemId = itemId,
                        title = title,
                        filePath = path,
                        isPlaying = true,
                        durationMs = p.duration.toLong(),
                        positionMs = resumeMs,
                        routeName = describeCurrentRoute(),
                        error = null,
                    )
                }
                startInForeground(title)
                startPositionTicker()
            }
            next.setOnCompletionListener {
                Hub.update { it.copy(isPlaying = false, positionMs = it.durationMs) }
                stopSelfPlaying(null)
            }
            next.setOnErrorListener { _, _, _ ->
                Hub.update { it.copy(isPlaying = false, error = "播放失败") }
                stopSelfPlaying(null)
                true
            }
            next.prepareAsync()
            player = next
        } catch (t: Throwable) {
            next.release()
            abandonFocus()
            Hub.update { it.copy(isPlaying = false, error = "无法播放：${t.message ?: "初始化失败"}") }
            stopSelf()
        }
    }

    private var ticker: kotlinx.coroutines.Job? = null

    private fun startPositionTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                val p = player ?: break
                if (Hub.currentState.isPlaying) {
                    Hub.update { it.copy(positionMs = p.currentPosition.toLong()) }
                }
                kotlinx.coroutines.delay(250)
            }
        }
    }

    private fun currentPosition(): Long = try {
        player?.currentPosition?.toLong() ?: 0L
    } catch (_: Exception) {
        0L
    }

    private fun stopSelfPlaying(reason: String?) {
        ticker?.cancel()
        player?.run {
            runCatching { stop() }
            release()
        }
        player = null
        abandonFocus()
        Hub.update {
            it.copy(isPlaying = false, error = reason ?: it.error)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun playbackAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(
            if (route == PlaybackRoute.EARPIECE) AudioAttributes.USAGE_VOICE_COMMUNICATION
            else AudioAttributes.USAGE_MEDIA,
        )
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun requestFocus(): Boolean {
        // AudioFocusRequest exists from API 26; minSdk is 24, so pre-O devices
        // use the deprecated listener-based API with the same policy.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes())
                .setOnAudioFocusChangeListener(::handleFocusChange)
                .build()
                .also { focusRequest = it }
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                ::handleFocusChange,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun handleFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                // Interruptions cancel this playback round entirely;
                // never auto-resume over another app.
                stopSelfPlaying("播放被其他应用中断")
            }
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.abandonAudioFocus(::handleFocusChange) }
        }
    }

    /** Reads the ACTUAL output device; never pretends speaker when routed elsewhere. */
    private fun describeCurrentRoute(): String {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val type = devices.firstOrNull { it.isSink }?.type ?: return "未知"
        return when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            -> "有线耳机"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙耳机"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
            else -> "设备 #$type"
        }
    }

    private fun startInForeground(title: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "语音播放",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "显示当前播放的生成语音" },
            )
        }
        val content = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_playback)
            .setContentTitle("ShineVoice 正在播放")
            .setContentText(title)
            .setContentIntent(content)
            .setOngoing(true)
            .addAction(0, "停止", stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        noisyReceiver?.let { runCatching { unregisterReceiver(it) } }
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        ticker?.cancel()
        player?.release()
        player = null
        abandonFocus()
        scope.cancel()
        Hub.detach(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Process-wide hub: the single source of truth for playback state and the
     * single entry point for commands. When the service is down, commands
     * start it (startForegroundService from allowed callers).
     */
    object Hub {
        private val _state = kotlinx.coroutines.flow.MutableStateFlow(PlaybackState())
        val state: kotlinx.coroutines.flow.StateFlow<PlaybackState> =
            _state as kotlinx.coroutines.flow.StateFlow<PlaybackState>
        val currentState: PlaybackState get() = _state.value

        private var service: PlaybackService? = null

        fun attach(service: PlaybackService) {
            this.service = service
        }

        fun detach(service: PlaybackService) {
            if (this.service === service) this.service = null
        }

        fun update(transform: (PlaybackState) -> PlaybackState) {
            _state.value = transform(_state.value)
        }

        fun play(context: Context, itemId: String, filePath: String, title: String) {
            val intent = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_ID, itemId)
                .putExtra(EXTRA_PATH, filePath)
                .putExtra(EXTRA_TITLE, title)
            start(context, intent)
        }

        fun stop(context: Context) {
            val running = service
            if (running != null) {
                running.stopSelfPlaying(null)
                return
            }
            start(context, Intent(context, PlaybackService::class.java).setAction(ACTION_STOP))
        }

        private fun start(context: Context, intent: Intent) {
            val running = service
            if (running != null) {
                running.onStartCommand(intent, 0, 0)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "shinevoice_playback"
        private const val NOTIFICATION_ID = 41
        const val ACTION_PLAY = "com.shinevoice.playback.PLAY"
        const val ACTION_STOP = "com.shinevoice.playback.STOP"
        const val EXTRA_ID = "id"
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
    }
}
