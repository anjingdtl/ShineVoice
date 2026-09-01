package com.shinevoice.core.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * 播放路由切换。听筒模式借用通话路由（USAGE_VOICE_COMMUNICATION +
 * 通信设备选择），播放结束后必须调用 [deactivate] 恢复默认媒体路由，
 * 否则系统会停留在通话模式影响其他应用出声。
 */
class AudioRouteManager(context: Context) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 非 null 表示系统当前处于听筒借用状态。 */
    private var earpieceActive = false

    /**
     * 在按 [route] 播放前调用。返回 false 表示听筒设备不可用
     * （仍会尝试旧接口兜底）；外放恒返回 true。
     */
    fun activate(route: PlaybackRoute): Boolean {
        deactivate()
        if (route == PlaybackRoute.SPEAKER) return true
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val earpiece = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            earpiece != null && audioManager.setCommunicationDevice(earpiece)
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            true
        }
        earpieceActive = true
        return routed
    }

    /** 播放停止/结束后调用，撤销通信路由并恢复正常音频模式；未激活时为空操作。 */
    fun deactivate() {
        if (!earpieceActive) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
        audioManager.mode = AudioManager.MODE_NORMAL
        earpieceActive = false
    }
}
