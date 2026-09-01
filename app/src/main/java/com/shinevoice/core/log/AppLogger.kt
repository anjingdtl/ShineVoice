package com.shinevoice.core.log

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Small logging boundary so providers never write secrets or UI concerns to
 * Logcat. Keeps an in-memory ring buffer of recent entries for the
 * 设置 → 高级 → 开发与诊断 view and diagnostic export; never logs credentials.
 */
class AppLogger(private val tag: String = "ShineVoice") {
    private val buffer = ArrayDeque<String>(BUFFER_CAPACITY)
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    private fun record(level: String, message: String) {
        if (buffer.size >= BUFFER_CAPACITY) buffer.removeFirst()
        buffer.addLast("${format.format(Date())} $level/$tag: $message")
    }

    fun d(message: String) {
        record("D", message)
        runCatching { Log.d(tag, message) }
    }

    fun i(message: String) {
        record("I", message)
        runCatching { Log.i(tag, message) }
    }

    fun w(message: String, throwable: Throwable? = null) {
        record("W", message + (throwable?.let { " (${it.message})" } ?: ""))
        runCatching {
            if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        record("E", message + (throwable?.let { " (${it.message})" } ?: ""))
        runCatching {
            if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
        }
    }

    /** Recent log lines, oldest first; diagnostics-only, contains no secrets. */
    @Synchronized
    fun recentLogs(): List<String> = buffer.toList()

    companion object {
        private const val BUFFER_CAPACITY = 200
    }
}
