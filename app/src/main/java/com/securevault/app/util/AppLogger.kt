package com.securevault.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * アプリ内診断ログ。Logcat 出力 + メモリ保持 + ファイル書き出しを提供する。
 */
@Singleton
class AppLogger @Inject constructor() {

    private val buffer = ConcurrentLinkedQueue<String>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, message: String) = log("D", tag, message)

    fun i(tag: String, message: String) = log("I", tag, message)

    fun w(tag: String, message: String, t: Throwable? = null) = log("W", tag, message, t)

    fun e(tag: String, message: String, t: Throwable? = null) = log("E", tag, message, t)

    private fun log(level: String, tag: String, message: String, t: Throwable? = null) {
        val timestamp = synchronized(dateFormat) {
            dateFormat.format(Date())
        }
        val line = "$timestamp $level/$tag: $message"
        buffer.add(line)
        t?.let { throwable ->
            buffer.add("  ${throwable.stackTraceToString().take(STACK_TRACE_MAX_LENGTH)}")
        }
        while (buffer.size > MAX_ENTRIES) {
            buffer.poll()
        }

        when (level) {
            "D" -> Log.d(tag, message, t)
            "I" -> Log.i(tag, message, t)
            "W" -> Log.w(tag, message, t)
            "E" -> Log.e(tag, message, t)
        }
    }

    fun getLines(): List<String> = buffer.toList()

    fun exportToFile(context: Context): File {
        val dir = File(context.filesDir, LOG_DIRECTORY_NAME)
        dir.mkdirs()
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "keypass_log_$dateStr.txt")
        file.writeText(buffer.joinToString("\n"))
        return file
    }

    fun clear() {
        buffer.clear()
    }

    private companion object {
        const val MAX_ENTRIES = 5000
        const val STACK_TRACE_MAX_LENGTH = 500
        const val LOG_DIRECTORY_NAME = "logs"
    }
}
