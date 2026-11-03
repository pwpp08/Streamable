package com.streamflixreborn.streamflix.downloads

import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.LanguageMatcher

/** Pure selection rules for downloads (kept separate so they can be unit tested). */
object DownloadRules {

    const val DEFAULT_MAX_HEIGHT = 1080
    const val SAVE_SPACE_MAX_HEIGHT = 720

    /** Servers whose stream is tried before giving up (IP/token-bound links fail at prepare time). */
    const val MAX_STREAM_ATTEMPTS = 3

    /** Upper bound on `getVideo` calls per download attempt, so dead servers can't stall it. */
    const val MAX_RESOLUTIONS = 8

    /**
     * Largest video size to download, as (width, height). A small tolerance keeps encodes such
     * as 1920x1088 inside the 1080p class.
     */
    fun videoSizeCap(saveSpace: Boolean): Pair<Int, Int> {
        val height = if (saveSpace) SAVE_SPACE_MAX_HEIGHT else DEFAULT_MAX_HEIGHT
        val width = height * 16 / 9
        return (width + 32) to (height + 16)
    }

    /** Sideloaded subtitles worth saving: provider defaults and those in spoken languages. */
    fun spokenSubtitles(subtitles: List<Video.Subtitle>, spoken: List<String>): List<Video.Subtitle> =
        subtitles.filter {
            it.initialDefault || LanguageMatcher.isSpoken(LanguageMatcher.normalize(it.label), spoken)
        }

    private val SUBTITLE_EXTENSIONS = listOf("vtt", "srt", "ass", "ssa", "ttml", "xml")

    /**
     * File extension for a downloaded subtitle. The player picks the subtitle MIME type from it,
     * so it must reflect the real format.
     */
    fun subtitleExtension(url: String, contentType: String?, head: String?): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        SUBTITLE_EXTENSIONS.firstOrNull { path.endsWith(".$it") }?.let { return it }
        val type = contentType?.lowercase().orEmpty()
        return when {
            "vtt" in type -> "vtt"
            "ttml" in type || "xml" in type -> "ttml"
            head?.trimStart('﻿', ' ', '\n', '\r')?.startsWith("WEBVTT") == true -> "vtt"
            else -> "srt"
        }
    }

    /** Ranked servers without the ones already tried; all of them again when every one was tried. */
    fun <T> untried(ranked: List<T>, tried: Collection<String>, name: (T) -> String): List<T> {
        val remaining = ranked.filterNot { server -> tried.any { it.equals(name(server), ignoreCase = true) } }
        return remaining.ifEmpty { ranked }
    }
}
