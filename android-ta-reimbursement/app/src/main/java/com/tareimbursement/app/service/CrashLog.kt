package com.tareimbursement.app.service

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime

/**
 * Keeps a local record of the last unexpected error (stack trace + app/OS version only — no
 * claim data). It never leaves the phone unless the user shares it from Settings.
 */
object CrashLog {
    fun file(context: Context) = File(context.filesDir, "crash/last-error.txt")

    fun install(context: Context, versionName: String) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { record(appContext, versionName, thread.name, error) } catch (_: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(context: Context, versionName: String, threadName: String, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val text = buildString {
            appendLine("TA Reimburse $versionName — ${LocalDateTime.now()}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: $threadName")
            appendLine()
            append(sw.toString().take(30_000))
        }
        file(context).apply { parentFile?.mkdirs() }.writeText(text)
    }

    fun exists(context: Context) = file(context).let { it.exists() && it.length() > 0 }
    fun clear(context: Context) { file(context).delete() }
}
