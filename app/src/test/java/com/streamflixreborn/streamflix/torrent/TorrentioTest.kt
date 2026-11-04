package com.streamflixreborn.streamflix.torrent

import com.streamflixreborn.streamflix.fragments.player.engine.ServerRanker
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.health.ProviderHealthChecker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentioTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("torrent/$name")!!.readBytes().toString(Charsets.UTF_8)

    private fun stream(
        title: String,
        seeders: Int = 50,
        sizeGb: Double = 4.0,
        site: String = "1337x",
        languages: String = "",
        height: Int = 1080,
    ) = TorrentStream(
        infoHash = "a".repeat(40), fileIdx = 0, release = title, fileName = null, seeders = seeders,
        sizeBytes = (sizeGb * (1L shl 30)).toLong(), site = site, languages = languages, height = height,
        trackers = emptyList(),
    )

    @Test
    fun parsesRealMovieResponse() {
        val streams = TorrentioParser.parse(fixture("torrentio_movie.json"))
        assertTrue(streams.size > 20)
        val web = streams.first { it.release.startsWith("The.Dark.Knight.2008.1080p.MA.WEB-DL.DDP5.1.H.264-Azkars") }
        assertEquals("e7aa68546a660907c232370789bccb7e016af0d6", web.infoHash)
        assertEquals(0, web.fileIdx)
        assertEquals(59, web.seeders)
        assertEquals(1080, web.height)
        assertEquals("ThePirateBay", web.site)
        assertEquals((8.72 * (1L shl 30)).toLong(), web.sizeBytes)
        assertEquals("The.Dark.Knight.2008.1080p.MA.WEB-DL.DDP5.1.H.264-Azkars.mkv", web.fileName)
    }

    @Test
    fun parsesMultiLineTitleWithFileAndLanguages() {
        val multi = TorrentioParser.parse(fixture("torrentio_movie.json"))
            .first { it.release.startsWith("The Christopher Nolan Collection") }
        assertEquals(6, multi.fileIdx)
        assertEquals("2008.The.Dark.Knight.1920x1080.BDRip.x264.TrueHD.mkv", multi.fileName)
        assertEquals(6, multi.seeders)
    }

    @Test
    fun garbageIsIgnored() {
        assertTrue(TorrentioParser.parse("not json").isEmpty())
        assertTrue(TorrentioParser.parse("{\"streams\":[{\"title\":\"x\"}]}").isEmpty())
        assertTrue(TorrentioParser.parse("{}").isEmpty())
    }

    @Test
    fun selectionKeepsOnlyPlayableCandidates() {
        val all = TorrentioParser.parse(fixture("torrentio_movie.json"))
        val picked = TorrentCandidateSelector.select(all)
        assertTrue(picked.isNotEmpty())
        assertTrue(picked.size <= 3)
        picked.forEach {
            assertEquals(1080, it.height)
            assertTrue(it.seeders >= TorrentCandidateSelector.MIN_SEEDERS)
            assertTrue(it.sizeBytes <= TorrentCandidateSelector.MAX_BYTES)
            val text = it.release + " " + it.fileName
            assertFalse(text, Regex("x265|hevc|av1|10 ?bit|hdr|remux", RegexOption.IGNORE_CASE).containsMatchIn(text))
            assertFalse(text, Regex("[\\u0400-\\u04FF]").containsMatchIn(text))
        }
        assertEquals(picked.map { it.site.lowercase() }.distinct().size, picked.size)
        // The well seeded H.264 YIFY encode wins over x265/10-bit releases and huge rips.
        assertEquals("a54926c2", picked.first().infoHash.take(8))
        assertEquals("Torrent 1080p (YTS)", TorrentServers.toServers(picked).first().name)
    }

    @Test
    fun seriesResponseYieldsCandidates() {
        val picked = TorrentCandidateSelector.select(TorrentioParser.parse(fixture("torrentio_series.json")))
        assertTrue(picked.isNotEmpty())
        picked.forEach { assertEquals(1080, it.height) }
    }

    @Test
    fun rejectsHevcLowSeedsHugeAndForeign() {
        assertFalse(TorrentCandidateSelector.isUsable(stream("Movie 2020 1080p BluRay x265 HEVC")))
        assertFalse(TorrentCandidateSelector.isUsable(stream("Movie 2020 1080p BluRay x264", seeders = 4)))
        assertFalse(TorrentCandidateSelector.isUsable(stream("Movie 2020 1080p BluRay x264", sizeGb = 20.0)))
        assertFalse(TorrentCandidateSelector.isUsable(stream("Movie 2020 1080p REMUX AVC")))
        assertFalse(TorrentCandidateSelector.isUsable(stream("Movie 2020 1080p WEB-DL H.264 10bit")))
        assertFalse(TorrentCandidateSelector.isUsable(stream("Film 2020 1080p BluRay x264", languages = "🇫🇷")))
        assertFalse(TorrentCandidateSelector.isUsable(stream("Movie 2020 720p BluRay x264", height = 720)))
        assertFalse(TorrentCandidateSelector.isUsable(stream("Movie 2025 1080p HDTS x264")))
        assertFalse(TorrentCandidateSelector.isUsable(stream("Movie 2025 1080p PreDVD x264")))
        assertFalse(TorrentCandidateSelector.isUsable(stream("Movie 2025 1080p HD-TC x264")))
        assertFalse(TorrentCandidateSelector.isUsable(stream("Movie 2025 1080p BluRay x264 DTS-HD")))
        assertFalse(TorrentCandidateSelector.isUsable(stream("Movie 2025 1080p WEB-DL x264 DDP5.1 Atmos")))
        assertTrue(TorrentCandidateSelector.isUsable(stream("Movie 2020 1080p BluRay x264")))
        assertTrue(TorrentCandidateSelector.isUsable(stream("Film 2020 1080p BluRay x264", languages = "🇭🇺")))
        assertTrue(TorrentCandidateSelector.isUsable(stream("Film 2020 1080p BluRay x264", languages = "🇬🇧 / 🇫🇷")))
    }

    @Test
    fun torrentUriRoundTrip() {
        val spec = TorrentSpec("A".repeat(40), 3, 1080, "The Movie (2020).mkv", listOf("udp://tracker.example:80/announce"))
        val parsed = TorrentSpec.parse(spec.toUri())!!
        assertEquals("a".repeat(40), parsed.infoHash)
        assertEquals(3, parsed.fileIdx)
        assertEquals(1080, parsed.height)
        assertEquals("The Movie (2020).mkv", parsed.name)
        assertEquals(listOf("udp://tracker.example:80/announce"), parsed.trackers)
        assertNull(TorrentSpec.parse("torrent://nothash/0"))
        assertNull(TorrentSpec.parse("https://example.com/a.mp4"))
        assertEquals(null, TorrentSpec.parse("torrent://${"b".repeat(40)}/-1")!!.fileIdx)
    }

    @Test
    fun serversAreNamedAndUnique() = runBlocking {
        val seen = mutableListOf<String>()
        val servers = TorrentServers(
            source = { path -> seen += path; fixture("torrentio_movie.json") },
            imdbIdFor = { "tt0468569" },
            enabled = { true },
        ).servers(Video.Type.Movie("155", "The Dark Knight", "2008-07-16", "", "tt0468569"))
        assertEquals(listOf("movie/tt0468569"), seen)
        assertTrue(servers.isNotEmpty())
        assertEquals(servers.size, servers.map { it.name }.distinct().size)
        assertEquals(servers.size, servers.map { it.id }.distinct().size)
        servers.forEach {
            assertTrue(it.name, it.name.startsWith("Torrent 1080p ("))
            assertTrue(ServerRanker.isTorrentServer(it.name))
            assertTrue(TorrentServers.isTorrentServer(it))
            assertNotNull(TorrentServers.videoFor(it).source)
        }
    }

    @Test
    fun episodePathAndDisabledSwitch() = runBlocking {
        val seen = mutableListOf<String>()
        val episode = Video.Type.Episode(
            "1", 4, "t", null, null,
            Video.Type.Episode.TvShow("1396", "Breaking Bad", null, null, null, "tt0903747"),
            Video.Type.Episode.Season(2, null),
        )
        TorrentServers({ seen += it; fixture("torrentio_series.json") }, { "tt0903747" }, { true }).servers(episode)
        assertEquals(listOf("series/tt0903747:2:4"), seen)
        seen.clear()
        assertTrue(TorrentServers({ seen += it; "" }, { "tt0903747" }, { false }).servers(episode).isEmpty())
        assertTrue(seen.isEmpty())
        assertTrue(TorrentServers({ null }, { null }, { true }).servers(episode).isEmpty())
    }

    @Test
    fun probeHeightReadsTorrentUriWithoutNetwork() = runBlocking {
        // An http client factory that fails the test if anything tries to use the network.
        val checker = ProviderHealthChecker(providers = emptyList(), httpClient = { error("network used") })
        val spec = TorrentSpec("c".repeat(40), 0, 1080, "x.mkv")
        assertEquals(1080, checker.probeHeight(Video(source = spec.toUri())))
        assertNull(checker.probeHeight(Video(source = TorrentSpec("c".repeat(40), 0, null).toUri())))
    }

    @Test
    fun torrentServersAlwaysRankAfterWeb() {
        val web = Video.Server("vix", "VixSrc")
        val web2 = Video.Server("zee", "Vidzee")
        val torrent = Video.Server("torrent:x:0", "Torrent 1080p (YTS)")
        val servers = listOf(web, web2, torrent)
        // A failing web server must not drop below an unknown torrent.
        val ranked = ServerRanker.rank(servers, score = { if (it == "VixSrc") 40 else 50 }, height = { null })
        assertEquals("Torrent 1080p (YTS)", ranked.last().name)
        val withHeight = ServerRanker.rank(servers, score = { 50 }, height = { if (it.startsWith("Torrent")) 1080 else 720 })
        // Even a torrent known to be 1080p only joins as the quality upgrade: it takes ~30 s to start.
        assertEquals("Torrent 1080p (YTS)", withHeight.last().name)
        val videasy = Video.Server("v", "Videasy Boise")
        val sticky = ServerRanker.rank(listOf(web, videasy, torrent), stickyName = "VixSrc", score = { null }, height = { if (it == "VixSrc") 720 else null })
        assertEquals("Videasy Boise", sticky.first().name)
    }
}
