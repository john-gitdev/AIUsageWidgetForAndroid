package com.example.claudewidget

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device log of refreshes, logins and errors, shown by the "Log" button in the app.
 * Logcat needs a computer, so this is how to see what happened on the phone.
 * Never log cookies, tokens, or bodies of successful responses.
 */
object AppLog {
    private const val FILE_NAME = "app_log.txt"

    /** When the file grows past this, the oldest half is dropped. */
    private const val MAX_BYTES = 128 * 1024

    private val lock = Any()

    fun i(context: Context, tag: String, message: String) = write(context, "I", tag, message, null)

    fun w(context: Context, tag: String, message: String, error: Throwable? = null) =
        write(context, "W", tag, message, error)

    fun e(context: Context, tag: String, message: String, error: Throwable? = null) =
        write(context, "E", tag, message, error)

    /** All log lines, newest first. */
    fun read(context: Context): String = synchronized(lock) {
        val file = file(context)
        if (file.exists()) file.readLines().asReversed().joinToString("\n") else ""
    }

    fun clear(context: Context) {
        synchronized(lock) { file(context).delete() }
    }

    private fun write(context: Context, level: String, tag: String, message: String, error: Throwable?) {
        when (level) {
            "E" -> Log.e(tag, message, error)
            "W" -> Log.w(tag, message, error)
            else -> Log.i(tag, message)
        }

        val detail = error?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val line = "$time $level $tag: $message$detail\n"

        try {
            synchronized(lock) {
                val file = file(context)
                file.appendText(line)
                if (file.length() > MAX_BYTES) {
                    val text = file.readText()
                    val keepFrom = text.indexOf('\n', text.length - MAX_BYTES / 2) + 1
                    file.writeText(text.substring(keepFrom))
                }
            }
        } catch (e: Exception) {
            Log.w("AppLog", "Couldn't write to the log file", e)
        }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
}
