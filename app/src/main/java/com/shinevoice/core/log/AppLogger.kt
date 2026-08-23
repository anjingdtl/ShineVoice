package com.shinevoice.core.log

import android.util.Log

/** Small logging boundary so providers never write secrets or UI concerns to Logcat. */
class AppLogger(private val tag: String = "ShineVoice") {
    fun d(message: String) {
        runCatching { Log.d(tag, message) }
    }

    fun i(message: String) {
        runCatching { Log.i(tag, message) }
    }

    fun w(message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
        }
    }
}
