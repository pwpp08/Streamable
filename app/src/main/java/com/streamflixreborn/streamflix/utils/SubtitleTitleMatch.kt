package com.streamflixreborn.streamflix.utils

import java.text.Normalizer

/**
 * Keeps OpenSubtitles results that belong to the title being played. The search is free text, so
 * "Musk" also finds films with Musk somewhere in a long name, and "Iron Man" finds Iron Man 3.
 * A result stays only if its film name is the same name (nothing added) and, when both sides
 * know a year, the same year.
 */
object SubtitleTitleMatch {

    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val plain = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.lowercase()
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    /** `"Show Name" Episode title` becomes `Show Name`; other names are returned as they are. */
    private fun seriesPart(name: String): String =
        Regex("^\"([^\"]+)\"").find(name.trim())?.groupValues?.get(1) ?: name

    private fun stripTrailingYear(name: String): String = name.replace(Regex("\\s*\\((\\d{4})\\)\\s*$"), "")

    private fun yearOf(text: String?): Int? =
        text?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toIntOrNull()

    private fun names(subtitle: OpenSubtitles.Subtitle, series: Boolean): List<String> =
        listOf(subtitle.movieNameEng, subtitle.movieName)
            .filterNotNull()
            .map { if (series) seriesPart(it) else stripTrailingYear(it) }
            .map(::normalize)
            .filter { it.isNotEmpty() }

    /**
     * @param imdbId the title's IMDb id ("tt1375666"), when known; a result for that id is always right.
     * @param series true for episodes: [title] is the show name and result names look like `"Show" Episode`.
     */
    fun filter(
        subtitles: List<OpenSubtitles.Subtitle>,
        title: String,
        year: String?,
        imdbId: String? = null,
        series: Boolean = false,
    ): List<OpenSubtitles.Subtitle> {
        val wantedName = normalize(title)
        val wantedYear = yearOf(year)
        val wantedImdb = imdbId?.removePrefix("tt")?.trimStart('0')?.takeIf { it.isNotEmpty() && !series }

        fun nameMatches(s: OpenSubtitles.Subtitle) = wantedName.isNotEmpty() && wantedName in names(s, series)
        fun imdbMatches(s: OpenSubtitles.Subtitle) =
            wantedImdb != null && s.idMovieImdb?.trimStart('0') == wantedImdb

        val sameTitle = subtitles.filter { imdbMatches(it) || nameMatches(it) }
        // Episodes carry the episode's year, not the show's; the year only separates films.
        if (series || wantedYear == null) return sameTitle

        fun yearDiff(s: OpenSubtitles.Subtitle): Int? = yearOf(s.movieYear)?.let { kotlin.math.abs(it - wantedYear) }
        val exact = sameTitle.filter { imdbMatches(it) || yearDiff(it).let { d -> d == null || d == 0 } }
        if (exact.isNotEmpty()) return exact
        // Festival and regional releases are sometimes a year apart between databases.
        return sameTitle.filter { imdbMatches(it) || yearDiff(it).let { d -> d == null || d <= 1 } }
    }
}
