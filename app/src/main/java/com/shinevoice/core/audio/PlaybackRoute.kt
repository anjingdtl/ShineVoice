package com.shinevoice.core.audio

/** 播放输出路由：外放走默认媒体路由（扬声器/耳机自动切换），听筒借用通话链路。 */
enum class PlaybackRoute(val storedName: String, val displayName: String) {
    SPEAKER("speaker", "外放"),
    EARPIECE("earpiece", "听筒");

    companion object {
        fun fromName(name: String?): PlaybackRoute =
            entries.firstOrNull { it.storedName == name } ?: SPEAKER
    }
}
