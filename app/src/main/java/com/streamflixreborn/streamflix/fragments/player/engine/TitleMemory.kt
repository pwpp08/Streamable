package com.streamflixreborn.streamflix.fragments.player.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.streamflixreborn.streamflix.models.Video
import org.json.JSONObject
import java.io.File

/**
 * What subtitle was on for a movie or an episode, so coming back to it brings back exactly that:
 * the same downloaded file (kept in app storage, so a cache clean-up can't lose it), the same
 * built-in track, or subtitles off. The server is remembered per title by [StickyServers].
 */
object TitleMemory {

    private const val TAG = "TitleMemory"
    private const val PREFS = "player_title_memory"
    private const val KEY_MAP = "subtitles"
    private const val DIR = "subtitle_memory"
    private const val MAX_ENTRIES = 200

    sealed interface Subtitle {
        data object Off : Subtitle
        data class Track(val language: String?, val label: String?) : Subtitle
        data class File(val path: String, val label: String?) : Subtitle
    }

    fun key(videoType: Video.Type): String = when (videoType) {
        is Video.Type.Movie -> "movie:${videoType.id}"
        is Video.Type.Episode -> "episode:${videoType.id}"
    }

    fun subtitle(context: Context, key: String): Subtitle? {
        val o = read(context).optJSONObject(key) ?: return null
        return when (o.optString("kind")) {
            "off" -> Subtitle.Off
            "track" -> Subtitle.Track(o.optString("language").ifEmpty { null }, o.optString("label").ifEmpty { null })
            "file" -> o.optString("path").takeIf { File(it).isFile }?.let { Subtitle.File(it, o.optString("label").ifEmpty { null }) }
            else -> null
        }
    }

    @Synchronized
    fun save(context: Context, key: String, subtitle: Subtitle) {
        if (subtitle(context, key) == subtitle) return
        val o = JSONObject()
        when (subtitle) {
            Subtitle.Off -> o.put("kind", "off")
            is Subtitle.Track -> o.put("kind", "track").put("language", subtitle.language.orEmpty()).put("label", subtitle.label.orEmpty())
            is Subtitle.File -> o.put("kind", "file").put("path", subtitle.path).put("label", subtitle.label.orEmpty())
        }
        val map = read(context)
        map.remove(key)
        map.put(key, o)
        // JSONObject keeps insertion order on Android, so the oldest entries come first.
        while (map.length() > MAX_ENTRIES) {
            val oldest = map.keys().asSequence().firstOrNull() ?: break
            map.optJSONObject(oldest)?.optString("path")?.takeIf { it.isNotEmpty() }?.let { File(it).delete() }
            map.remove(oldest)
        }
        prefs(context).edit().putString(KEY_MAP, map.toString()).apply()
        Log.d(TAG, "$key -> $subtitle")
    }

    /** Copies a downloaded subtitle file into app storage and returns where it now lives. */
    fun keepFile(context: Context, key: String, uri: Uri): String? = runCatching {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val ext = uri.lastPathSegment?.substringAfterLast('.', "srt")?.take(5)?.ifEmpty { "srt" } ?: "srt"
        val target = File(dir, key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + "." + ext)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: uri.path?.let { File(it).readBytes() }
            ?: return null
        target.writeBytes(bytes)
        target.path
    }.onFailure { Log.w(TAG, "could not keep subtitle file: ${it.message}") }.getOrNull()

    private fun read(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString(KEY_MAP, null) ?: "{}") }.getOrElse { JSONObject() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Brings back a title's remembered subtitle once its tracks are known, then keeps the memory up to
 * date with whatever the viewer picks. One per player screen and title.
 */
@OptIn(UnstableApi::class)
class SubtitleRecall(
    private val context: Context,
    private val key: String,
    private val showFile: (Uri, String?) -> Unit,
    private val fileShowing: () -> Boolean,
) {
    private var restored = false

    /** True when this title had a subtitle choice saved before this screen opened (then nothing is fetched on its own). */
    val hadMemory: Boolean = TitleMemory.subtitle(context, key) != null

    /** Call from the player's onTracksChanged, after the automatic subtitle choice ran. */
    fun onTracksChanged(player: Player, tracks: Tracks) {
        if (tracks.groups.isEmpty()) return
        if (!restored) {
            restored = true
            restore(player, tracks)
            return
        }
        remember(player, tracks)
    }

    /** A downloaded subtitle file went on screen. */
    fun onFileShown(uri: Uri, label: String?) {
        if (uri.path?.startsWith(File(context.filesDir, "subtitle_memory").path) == true) return
        val path = TitleMemory.keepFile(context, key, uri) ?: return
        TitleMemory.save(context, key, TitleMemory.Subtitle.File(path, label))
    }

    private fun restore(player: Player, tracks: Tracks) {
        val saved = TitleMemory.subtitle(context, key) ?: return
        Log.d("TitleMemory", "restoring $key: $saved")
        when (saved) {
            TitleMemory.Subtitle.Off -> player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            is TitleMemory.Subtitle.File -> showFile(File(saved.path).toUri(), saved.label)
            is TitleMemory.Subtitle.Track -> {
                val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                fun find(match: (androidx.media3.common.Format) -> Boolean) = groups.firstNotNullOfOrNull { g ->
                    (0 until g.length).firstOrNull { g.isTrackSupported(it) && match(g.getTrackFormat(it)) }?.let { g to it }
                }
                val hit = (if (saved.label != null) find { it.label == saved.label } else null)
                    ?: saved.language?.let { lang -> find { LanguageMatcherCompat.same(it.language, it.label, lang) } }
                    ?: return
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(hit.first.mediaTrackGroup, hit.second))
                    .build()
            }
        }
    }

    private fun remember(player: Player, tracks: Tracks) {
        // A file on screen was saved when it was shown.
        if (fileShowing()) return
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (groups.isEmpty()) return
        val disabled = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
        val selected = groups.firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
        val subtitle = when {
            disabled || selected == null -> TitleMemory.Subtitle.Off
            else -> TitleMemory.Subtitle.Track(com.streamflixreborn.streamflix.utils.LanguageMatcher.detect(selected.language, selected.label), selected.label)
        }
        TitleMemory.save(context, key, subtitle)
    }
}

private object LanguageMatcherCompat {
    fun same(language: String?, label: String?, wanted: String): Boolean =
        com.streamflixreborn.streamflix.utils.LanguageMatcher.detect(language, label) == wanted
}
