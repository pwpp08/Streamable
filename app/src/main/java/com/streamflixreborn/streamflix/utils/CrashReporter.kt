package com.streamflixreborn.streamflix.utils

import android.app.ActivityManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.streamflixreborn.streamflix.BuildConfig
import java.io.File
import java.util.Date

/**
 * Leaves a trace when the app dies, so a crash that happens at launch can be diagnosed without a computer:
 * the error goes to Download/Streamable/Streamflix-crash.txt and into a notification, and Android's own
 * record of why the previous runs ended (crash, native crash, killed...) goes to Streamflix-exits.txt.
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val CRASH_FILE = "Streamflix-crash.txt"
    private const val EXITS_FILE = "Streamflix-exits.txt"
    private const val CHANNEL = "crash_reports"
    private const val NOTIFICATION_ID = 7_297
    private const val MAX_CHARS = 12_000

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { report(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    internal fun header(app: Context): String =
        "StreamFlix ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n${Date()}\n"

    private fun report(app: Application, thread: Thread, error: Throwable) {
        val text = (header(app) + "Thread: ${thread.name}\n\n" + Log.getStackTraceString(error)).take(MAX_CHARS)
        runCatching { File(app.filesDir, "last-crash.txt").writeText(text) }
        runCatching { writeDownload(app, CRASH_FILE, text) }
        runCatching { notify(app, text) }
    }

    private fun notify(app: Application, text: String) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Crash reports", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val firstLines = text.lines().drop(2).filter { it.isNotBlank() }.take(14).joinToString("\n")
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("StreamFlix closed unexpectedly")
                .setContentText(firstLines.lineSequence().firstOrNull().orEmpty())
                .setStyle(NotificationCompat.BigTextStyle().bigText(firstLines))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Called at every start (off the main thread): what Android says happened to the previous runs. */
    fun recordExitReasons(app: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exits = am.getHistoricalProcessExitReasons(app.packageName, 0, 6)
            if (exits.isEmpty()) return
            val out = StringBuilder(header(app)).append("\nAndroid's record of how earlier runs ended (newest first):\n")
            exits.forEachIndexed { i, info ->
                out.append("\n#").append(i).append(' ').append(info.toString()).append('\n')
                // A native crash comes with a trace of where it happened.
                if (info.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    runCatching {
                        info.traceInputStream?.bufferedReader()?.use { r -> out.append(r.readText().take(3_500)).append('\n') }
                    }
                }
            }
            writeDownload(app, EXITS_FILE, out.toString().take(MAX_CHARS))
        }.onFailure { Log.w(TAG, "could not record exit reasons", it) }
    }

    internal fun writeDownload(context: Context, name: String, text: String) {
        if (Build.VERSION.SDK_INT < 29) return
        val resolver = context.contentResolver
        runCatching {
            resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name))
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Streamable")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
