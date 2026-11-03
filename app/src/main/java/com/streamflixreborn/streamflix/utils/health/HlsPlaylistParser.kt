package com.streamflixreborn.streamflix.utils.health

/** Pure helpers for reading HLS playlists; no Android or network dependencies. */
object HlsPlaylistParser {

    data class Variant(val width: Int, val height: Int)

    private val resolutionRegex = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)

    fun isPlaylist(text: String): Boolean = text.trimStart('﻿', ' ', '\n', '\r', '\t').startsWith("#EXTM3U")

    fun isMasterPlaylist(text: String): Boolean = text.contains("#EXT-X-STREAM-INF", ignoreCase = true)

    /** Variants declared by `#EXT-X-STREAM-INF` lines that carry a RESOLUTION attribute. */
    fun variants(text: String): List<Variant> = text.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }
        .mapNotNull { line ->
            val match = resolutionRegex.find(line) ?: return@mapNotNull null
            val width = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val height = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            if (width <= 0 || height <= 0) null else Variant(width, height)
        }
        .toList()

    /**
     * Best quality class of a master playlist, or null when the playlist is a media playlist
     * or declares no RESOLUTION. Letterboxed variants count by width, so 1920x800 is 1080.
     */
    fun maxHeight(text: String): Int? = variants(text).maxOfOrNull { qualityHeight(it.width, it.height) }

    /** Height of the 16:9 frame the picture fits in: 1920x800 -> 1080, 1280x720 -> 720. */
    fun qualityHeight(width: Int, height: Int): Int = maxOf(height, width * 9 / 16)

    /** "1080p", "720p", or "?" when unknown. */
    fun label(height: Int?): String = if (height == null || height <= 0) "?" else "${height}p"
}
