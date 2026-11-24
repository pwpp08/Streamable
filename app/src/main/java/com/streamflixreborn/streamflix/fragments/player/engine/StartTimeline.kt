package com.streamflixreborn.streamflix.fragments.player.engine

import android.os.SystemClock
import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.utils.CrashReporter
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Where the time goes between pressing play and the first picture. Each start writes a few lines to
 * Download/Streamable/Streamflix-timing.txt (the last 25 starts), so slow starts can be looked at with real
 * numbers. Holds no links: addon messages that mention an address are dropped.
 */
object StartTimeline {
    private const val TAG = "StartTimeline"
    private const val KEEP = 25
    private const val ADDON_LINES = 60

    private class Session(val title: String, val begun: Long = SystemClock.elapsedRealtime(), val wall: Long = System.currentTimeMillis()) {
        val marks = ArrayList<Pair<Long, String>>()
    }

    private val lock = Any()
    private var session: Session? = null
    private val addonLog = ArrayDeque<Pair<Long, String>>()
    private val history = ArrayDeque<String>()

    /** What the addon code says it is doing (kept for the next start that looks at it). */
    fun addon(message: String) {
        if (message.contains("http", ignoreCase = true)) return
        synchronized(lock) {
            addonLog.addLast(System.currentTimeMillis() to message)
            while (addonLog.size > ADDON_LINES) addonLog.removeFirst()
        }
    }

    fun begin(title: String) {
        synchronized(lock) { session = Session(title).also { it.marks += 0L to "player opened" } }
    }

    fun mark(text: String) {
        synchronized(lock) {
            val s = session ?: return
            s.marks += (SystemClock.elapsedRealtime() - s.begun) to text
        }
    }

    /** Closes the start (first picture, or a failure) and writes the file. */
    fun finish(text: String) {
        val (s, lines) = synchronized(lock) {
            val current = session ?: return
            session = null
            current.marks += (SystemClock.elapsedRealtime() - current.begun) to text
            val since = current.wall - 120_000L
            current to addonLog.filter { it.first >= since }.map {
                val ago = it.first - current.wall
                "    addon ${if (ago >= 0) "+" else ""}${ago / 1000.0}s: ${it.second}"
            }
        }
        val entry = buildString {
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(s.wall))).append(" | ").append(s.title).append('\n')
            s.marks.forEach { (ms, what) -> append("  +").append(ms).append(" ms  ").append(what).append('\n') }
            if (lines.isNotEmpty()) {
                append("  (addon messages, seconds relative to opening the player)\n")
                lines.forEach { append(it).append('\n') }
            }
        }
        Thread({ save(entry) }, "start-timeline").apply { isDaemon = true }.start()
    }

    private fun save(entry: String) {
        runCatching {
            val all = synchronized(lock) {
                history.addFirst(entry)
                while (history.size > KEEP) history.removeLast()
                history.joinToString("\n")
            }
            val app = StreamFlixApp.instance
            val text = CrashReporter.header(app) + "\nNewest start first.\n\n" + all
            runCatching { File(app.filesDir, "timing.txt").writeText(text) }
            CrashReporter.writeDownload(app, "Streamflix-timing.txt", text)
        }.onFailure { Log.w(TAG, "could not save the timing", it) }
    }
}
