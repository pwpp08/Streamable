package com.streamflixreborn.streamflix.fragments.player.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import com.streamflixreborn.streamflix.utils.SubtitleOffset
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

private const val READ_TAG = "SideloadedSubtitles"

/**
 * Reads and parses a downloaded subtitle file. Never throws: a problem is logged with its real
 * cause and an empty list is returned, so the caller can say so instead of crashing.
 */
fun readSubtitleFile(context: Context, uri: Uri): List<SubtitleLine> {
    val bytes = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        Log.w(READ_TAG, "Could not open $uri through the content resolver: ${e.message}")
        null
    } ?: uri.path?.let { path ->
        try {
            File(path).readBytes()
        } catch (e: Exception) {
            Log.w(READ_TAG, "Could not read $path: ${e.message}")
            null
        }
    }
    if (bytes == null || bytes.isEmpty()) {
        Log.w(READ_TAG, "Subtitle file is missing or empty: $uri")
        return emptyList()
    }
    return try {
        SubtitleFileParser.parse(SubtitleFileParser.decode(bytes)).also {
            if (it.isEmpty()) Log.w(READ_TAG, "No timed lines found in ${bytes.size} bytes of $uri")
        }
    } catch (t: Throwable) {
        Log.e(READ_TAG, "Subtitle parsing failed for $uri", t)
        emptyList()
    }
}

/** One timed line of an SRT / WebVTT file. */
data class SubtitleLine(val startMs: Long, val endMs: Long, val text: String)

/** Reads SRT and WebVTT text. Anything it doesn't understand is skipped rather than failing the file. */
object SubtitleFileParser {

    private val TIME_LINE = Regex(
        """((?:\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d{1,2}:)?\d{1,2}:\d{2}[.,]\d{1,3})"""
    )
    // Braces are escaped: Android's regex engine (ICU) rejects a bare "}" that Java accepts.
    private val ASS_OVERRIDE = Regex("""\{\\[^\}]*\}""")
    private val BLOCK_BREAK = Regex("\n{2,}")

    /** UTF-8 when the bytes are valid UTF-8, otherwise the Windows-1252 most older subtitle files use. */
    fun decode(bytes: ByteArray): String {
        val hasBom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val body = if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (_: CharacterCodingException) {
            String(body, charset("windows-1252"))
        }
    }

    fun parse(text: String): List<SubtitleLine> {
        val lines = ArrayList<SubtitleLine>()
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').split(BLOCK_BREAK)
        for (block in blocks) {
            val rows = block.lines()
            val timeIndex = rows.indexOfFirst { TIME_LINE.containsMatchIn(it) }
            if (timeIndex < 0) continue
            val match = TIME_LINE.find(rows[timeIndex]) ?: continue
            val start = parseTime(match.groupValues[1]) ?: continue
            val end = parseTime(match.groupValues[2]) ?: continue
            val body = rows.drop(timeIndex + 1)
                .joinToString("\n") { ASS_OVERRIDE.replace(it, "").trim() }
                .trim()
            if (body.isEmpty() || end <= start) continue
            lines += SubtitleLine(start, end, body)
        }
        return lines.sortedBy { it.startMs }
    }

    /** `00:01:02,345`, `01:02.345` or `1:02:03.4`. */
    fun parseTime(value: String): Long? {
        val parts = value.replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val seconds = parts.last().split('.')
        val whole = seconds[0].toLongOrNull() ?: return null
        val fraction = seconds.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        return ((hours * 60 + minutes) * 60 + whole) * 1000 + fraction
    }

    /** Lines showing at [timeMs] (several can overlap), in file order. */
    fun activeAt(lines: List<SubtitleLine>, timeMs: Long): List<SubtitleLine> {
        if (lines.isEmpty() || timeMs < 0) return emptyList()
        var low = 0
        var high = lines.size - 1
        var last = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].startMs <= timeMs) {
                last = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (last < 0) return emptyList()
        val result = ArrayList<SubtitleLine>(2)
        var index = last
        while (index >= 0 && last - index < LOOKBACK) {
            if (timeMs < lines[index].endMs) result += lines[index]
            index--
        }
        return result.reversed()
    }

    private const val LOOKBACK = 6
}

/**
 * A subtitle file shown on top of the video without touching the media item, so loading one never
 * re-requests the stream (single-use links, other servers) or interrupts playback. The player's own
 * subtitle track is switched off while this is showing and takes over again when the viewer picks
 * a built-in track.
 */
@OptIn(UnstableApi::class)
class SideloadedSubtitles(
    private val player: () -> Player?,
    private val subtitleView: () -> SubtitleView?,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var lines: List<SubtitleLine> = emptyList()
    private var shown: List<SubtitleLine> = emptyList()
    private var cues = HashMap<SubtitleLine, Cue>()
    private var sinceResend = 0

    var label: String? = null
        private set
    val isActive get() = lines.isNotEmpty()

    private val ticker = object : Runnable {
        override fun run() {
            if (!isActive) return
            refresh()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            // The viewer chose a built-in subtitle (or switched them on): that wins over the loaded file.
            if (isActive && C.TRACK_TYPE_TEXT !in parameters.disabledTrackTypes) clear()
        }
    }
    private var listening: Player? = null

    fun show(subtitle: List<SubtitleLine>, name: String?) {
        val exo = player() ?: return
        if (subtitle.isEmpty()) return
        lines = subtitle
        label = name
        cues = HashMap()
        shown = emptyList()
        if (listening !== exo) {
            listening?.removeListener(listener)
            exo.addListener(listener)
            listening = exo
        }
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    /** The player was replaced by another one: keep watching it, and keep the built-in subtitles off. */
    fun rebind() {
        if (lines.isEmpty()) return
        val exo = player() ?: return
        if (listening !== exo) {
            listening?.removeListener(listener)
            exo.addListener(listener)
            listening = exo
        }
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun clear() {
        if (lines.isEmpty()) return
        lines = emptyList()
        label = null
        shown = emptyList()
        handler.removeCallbacks(ticker)
        subtitleView()?.setCues(emptyList())
    }

    fun release() {
        handler.removeCallbacks(ticker)
        listening?.removeListener(listener)
        listening = null
        lines = emptyList()
    }

    private fun refresh() {
        val exo = player() ?: return
        val view = subtitleView() ?: return
        // Same clock as the built-in subtitles: a positive offset delays the captions.
        val time = exo.currentPosition - SubtitleOffset.offsetMs
        val active = SubtitleFileParser.activeAt(lines, time)
        if (active == shown && ++sinceResend < RESEND_EVERY) return
        sinceResend = 0
        shown = active
        view.setCues(active.map { line -> cues.getOrPut(line) { cueFor(line) } })
    }

    private fun cueFor(line: SubtitleLine): Cue {
        val html = line.text.replace("\n", "<br>")
        val spanned = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        var end = spanned.length
        while (end > 0 && spanned[end - 1] == '\n') end--
        return Cue.Builder().setText(spanned.subSequence(0, end)).build()
    }

    private companion object {
        const val TICK_MS = 120L

        /** The player's own (empty) cue output must not leave a loaded subtitle blank. */
        const val RESEND_EVERY = 8
    }
}
