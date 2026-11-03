package com.streamflixreborn.streamflix.downloads

import com.streamflixreborn.streamflix.models.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRulesTest {

    private val episode = Video.Type.Episode(
        id = "ep-1",
        number = 2,
        title = "Pilot",
        poster = "p.jpg",
        overview = "o",
        tvShow = Video.Type.Episode.TvShow("show-1", "Show", "sp.jpg", "sb.jpg", "2020-01-01", "tt1"),
        season = Video.Type.Episode.Season(1, "Season 1"),
    )
    private val movie = Video.Type.Movie("m-1", "Movie", "2021-05-05", "mp.jpg", null)

    @Test
    fun videoTypesRoundTripThroughMetadata() {
        val metadata = DownloadMetadata(
            title = "Show",
            subtitle = "S1 E2 • Pilot",
            poster = "p.jpg",
            provider = "Prov",
            server = "A",
            triedServers = listOf("A"),
            videoType = StoredVideoType.of(episode),
            subtitles = listOf(LocalSubtitle("English", "/x/0.srt", true)),
        )
        val decoded = DownloadMetadata.decode(metadata.encode())
        assertEquals(metadata, decoded)
        assertEquals(episode, decoded!!.videoType.toVideoType())
        assertEquals(movie, StoredVideoType.of(movie).toVideoType())
    }

    @Test
    fun garbageMetadataDecodesToNull() {
        assertNull(DownloadMetadata.decode(null))
        assertNull(DownloadMetadata.decode(ByteArray(0)))
        assertNull(DownloadMetadata.decode("not json".toByteArray()))
    }

    @Test
    fun idsSeparateProvidersAndKinds() {
        assertEquals("Prov|episode|ep-1", DownloadIds.of("Prov", episode))
        assertEquals("Prov|movie|m-1", DownloadIds.of("Prov", movie))
        assertNotEquals(DownloadIds.of("A", movie), DownloadIds.of("B", movie))
        val folder = DownloadIds.folderName("Prov|movie|a/b?c")
        assertTrue(folder.matches(Regex("[A-Za-z0-9._-]+")))
    }

    @Test
    fun videoCapFollowsSaveSpace() {
        assertEquals(1952 to 1096, DownloadRules.videoSizeCap(saveSpace = false))
        assertEquals(1312 to 736, DownloadRules.videoSizeCap(saveSpace = true))
    }

    @Test
    fun onlySpokenOrDefaultSubtitlesAreKept() {
        val subs = listOf(
            Video.Subtitle("English", "https://x/en.vtt"),
            Video.Subtitle("Magyar", "https://x/hu.srt"),
            Video.Subtitle("Deutsch", "https://x/de.srt"),
            Video.Subtitle("Italiano", "https://x/it.srt", initialDefault = true),
        )
        val kept = DownloadRules.spokenSubtitles(subs, listOf("en", "hu")).map { it.label }
        assertEquals(listOf("English", "Magyar", "Italiano"), kept)
    }

    @Test
    fun subtitleExtensionComesFromUrlTypeOrContent() {
        assertEquals("vtt", DownloadRules.subtitleExtension("https://x/a.vtt?token=1", null, null))
        assertEquals("srt", DownloadRules.subtitleExtension("https://x/a.SRT", "text/plain", null))
        assertEquals("vtt", DownloadRules.subtitleExtension("https://x/sub?id=3", "text/vtt", null))
        assertEquals("vtt", DownloadRules.subtitleExtension("https://x/sub", null, "﻿WEBVTT\n"))
        assertEquals("srt", DownloadRules.subtitleExtension("https://x/sub", null, "1\n00:00"))
    }

    @Test
    fun triedServersAreSkippedUntilAllWereTried() {
        val ranked = listOf("A", "B", "C")
        assertEquals(listOf("B", "C"), DownloadRules.untried(ranked, listOf("a")) { it })
        assertEquals(ranked, DownloadRules.untried(ranked, listOf("A", "B", "C")) { it })
    }
}
