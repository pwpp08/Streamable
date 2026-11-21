package com.streamflixreborn.streamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTitleMatchTest {
    private fun sub(name: String, year: String?, imdb: String? = null) =
        OpenSubtitles.Subtitle(movieName = name, movieNameEng = name, movieYear = year, idMovieImdb = imdb)

    @Test
    fun `iron man does not pick iron man 3`() {
        val found = listOf(sub("Iron Man 3", "2013"), sub("Iron Man", "2008"), sub("Iron Man 2", "2010"))
        val kept = SubtitleTitleMatch.filter(found, "Iron Man", "2008-04-30")
        assertEquals(listOf("Iron Man"), kept.map { it.movieName })
    }

    @Test
    fun `musk does not match longer names containing it`() {
        val found = listOf(sub("Elon Musk: The Real Story", "2023"), sub("Musk", "2025"), sub("Musk Rat Love", "1999"))
        assertEquals(listOf("Musk"), SubtitleTitleMatch.filter(found, "Musk", "2025").map { it.movieName })
    }

    @Test
    fun `same name from another year is rejected, a year off is a fallback`() {
        val remakes = listOf(sub("Dune", "1984"), sub("Dune", "2021"))
        assertEquals(listOf("2021"), SubtitleTitleMatch.filter(remakes, "Dune", "2021").map { it.movieYear })
        assertEquals(listOf("2022"), SubtitleTitleMatch.filter(listOf(sub("Dune", "2022")), "Dune", "2021").map { it.movieYear })
        assertEquals(emptyList<String>(), SubtitleTitleMatch.filter(listOf(sub("Dune", "1984")), "Dune", "2021").map { it.movieYear })
    }

    @Test
    fun `imdb id always matches and punctuation is ignored`() {
        val localized = listOf(sub("A Eredet", "2010", imdb = "1375666"))
        assertEquals(1, SubtitleTitleMatch.filter(localized, "Inception", "2010", imdbId = "tt1375666").size)
        assertEquals(1, SubtitleTitleMatch.filter(listOf(sub("Spider-Man: Homecoming", "2017")), "Spider Man Homecoming", "2017").size)
    }

    @Test
    fun `episodes match the show name inside the quotes`() {
        val found = listOf(sub("\"Breaking Bad\" Pilot", "2008"), sub("\"Breaking Bad: El Camino\" Pilot", "2019"), sub("\"Better Call Saul\" Uno", "2015"))
        assertEquals(1, SubtitleTitleMatch.filter(found, "Breaking Bad", "2008-01-20", series = true).size)
    }
}
