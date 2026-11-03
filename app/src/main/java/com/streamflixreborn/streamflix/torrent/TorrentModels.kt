package com.streamflixreborn.streamflix.torrent

import java.net.URLDecoder
import java.net.URLEncoder

/** One stream offered by a Torrentio-style addon. */
data class TorrentStream(
    val infoHash: String,
    val fileIdx: Int?,
    val release: String,
    val fileName: String?,
    val seeders: Int,
    val sizeBytes: Long,
    val site: String,
    val languages: String,
    val height: Int,
    val trackers: List<String>,
)

/** `torrent://<infoHash>/<fileIdx>?h=1080&n=<name>&tr=<tracker>&tr=...` */
data class TorrentSpec(
    val infoHash: String,
    val fileIdx: Int?,
    val height: Int?,
    val name: String? = null,
    val trackers: List<String> = emptyList(),
) {
    fun toUri(): String = buildString {
        append(SCHEME).append("://").append(infoHash.lowercase()).append('/').append(fileIdx ?: -1)
        val params = mutableListOf<String>()
        height?.let { params += "h=$it" }
        name?.let { params += "n=" + URLEncoder.encode(it, "UTF-8") }
        trackers.take(MAX_TRACKERS).forEach { params += "tr=" + URLEncoder.encode(it, "UTF-8") }
        if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
    }

    companion object {
        const val SCHEME = "torrent"
        private const val MAX_TRACKERS = 6
        private val HASH = Regex("[0-9a-f]{40}")

        fun isTorrentUri(source: String?): Boolean =
            source != null && source.startsWith("$SCHEME://", ignoreCase = true)

        /** Null when [uri] is not a well-formed torrent URI. */
        fun parse(uri: String): TorrentSpec? {
            if (!isTorrentUri(uri)) return null
            val rest = uri.substring(SCHEME.length + 3)
            val path = rest.substringBefore('?')
            val query = if (rest.contains('?')) rest.substringAfter('?') else ""
            val hash = path.substringBefore('/').lowercase()
            if (!HASH.matches(hash)) return null
            val idx = path.substringAfter('/', "").toIntOrNull()?.takeIf { it >= 0 }
            var height: Int? = null
            var name: String? = null
            val trackers = mutableListOf<String>()
            for (pair in query.split('&').filter { it.isNotEmpty() }) {
                val key = pair.substringBefore('=')
                val value = runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
                when (key) {
                    "h" -> height = value.toIntOrNull()
                    "n" -> name = value
                    "tr" -> if (value.isNotBlank()) trackers += value
                }
            }
            return TorrentSpec(hash, idx, height, name, trackers)
        }

        /** Height encoded in a torrent URI, or null; needs no network. */
        fun heightOf(uri: String): Int? = parse(uri)?.height
    }
}
