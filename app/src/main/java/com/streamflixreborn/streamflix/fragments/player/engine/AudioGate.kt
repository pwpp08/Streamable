package com.streamflixreborn.streamflix.fragments.player.engine

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.LanguageMatcher
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.health.HlsPlaylistParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

/**
 * Spots streams whose HLS audio renditions are all in languages the user doesn't speak (e.g. only
 * French and Italian dubs), so another server can be tried first.
 */
object AudioGate {

    private const val FETCH_TIMEOUT_MS = 2_500L
    private const val MAX_BYTES = 64 * 1024L
    private val attr = Regex("""(LANGUAGE|NAME)="([^"]*)"""", RegexOption.IGNORE_CASE)

    /** True only when the playlist lists audio renditions, all identifiable, and none is spoken. */
    fun lacksSpokenAudio(playlist: String, spoken: List<String>): Boolean {
        if (spoken.isEmpty() || !HlsPlaylistParser.isPlaylist(playlist)) return false
        val audio = playlist.lineSequence().filter { it.startsWith("#EXT-X-MEDIA", true) && it.contains("TYPE=AUDIO", true) }.toList()
        if (audio.isEmpty()) return false
        val languages = audio.map { line ->
            val values = attr.findAll(line).associate { it.groupValues[1].uppercase() to it.groupValues[2] }
            LanguageMatcher.detect(values["LANGUAGE"], values["NAME"])
        }
        if (languages.any { it == null }) return false
        return languages.none { LanguageMatcher.isSpoken(it, spoken) }
    }

    /** Reads the stream's master playlist (inline or over HTTP) and applies [lacksSpokenAudio]. */
    suspend fun lacksSpokenAudio(video: Video, spoken: List<String>): Boolean {
        val text = playlistText(video) ?: return false
        return lacksSpokenAudio(text, spoken)
    }

    private suspend fun playlistText(video: Video): String? {
        val source = video.source
        if (source.startsWith("data:", ignoreCase = true)) {
            val comma = source.indexOf(',')
            if (comma < 0) return null
            return runCatching {
                if (source.substring(5, comma).endsWith(";base64", true)) {
                    String(android.util.Base64.decode(source.substring(comma + 1), android.util.Base64.DEFAULT), Charsets.UTF_8)
                } else java.net.URLDecoder.decode(source.substring(comma + 1), "UTF-8")
            }.getOrNull()
        }
        if (!source.startsWith("http", ignoreCase = true)) return null
        if (video.type?.contains("mpegurl", true) != true && !source.contains(".m3u8", true)) return null
        return withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            runCatching {
                runInterruptible(Dispatchers.IO) {
                    val builder = Request.Builder().url(source)
                    video.headers?.forEach { (k, v) -> runCatching { builder.header(k, v) } }
                    NetworkClient.default.newCall(builder.build()).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        response.body?.source()?.let { it.request(MAX_BYTES); it.buffer.clone().readUtf8() }
                    }
                }
            }.getOrNull()
        }
    }
}
