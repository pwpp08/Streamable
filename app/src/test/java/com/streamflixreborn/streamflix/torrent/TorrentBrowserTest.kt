package com.streamflixreborn.streamflix.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentBrowserTest {

    private fun stream(release: String, seeders: Int, gb: Double, height: Int, hash: String = release.hashCode().toString(16).padStart(40, '0')) =
        TorrentStream(hash, null, release, null, seeders, (gb * 1024 * 1024 * 1024).toLong(), "site", "", height, emptyList())

    private val options = listOf(
        TorrentBrowser.describe(stream("Movie.2020.2160p.UHD.BluRay.x265.HDR.DTS-HD", 40, 22.0, 2160), "MediaFusion"),
        TorrentBrowser.describe(stream("Movie.2020.1080p.BluRay.x264.DDP5.1", 300, 8.5, 1080), "Torrentio"),
        TorrentBrowser.describe(stream("Movie.2020.1080p.WEB-DL.x265.AAC", 120, 2.1, 1080), "Torrentio"),
        TorrentBrowser.describe(stream("Movie.2020.720p.HDTV.x264", 15, 1.2, 720), "MediaFusion"),
    )

    @Test fun describeReadsTagsFromTheReleaseName() {
        val o = options[0]
        assertEquals(2160, o.resolution)
        assertEquals("BluRay", o.source)
        assertEquals("x265", o.codec)
        assertTrue(o.hdr)
        assertEquals("DTS", o.audio)
        assertEquals("WEB-DL", options[2].source)
    }

    @Test fun filtersCombine() {
        val f = TorrentFilter(resolutions = setOf(1080), codecs = setOf("x265"))
        assertEquals(listOf(options[2]), TorrentBrowser.filter(options, f))
        assertEquals(2, TorrentBrowser.filter(options, TorrentFilter(minSeeders = 100)).size)
        assertEquals(listOf(options[0]), TorrentBrowser.filter(options, TorrentFilter(hdrOnly = true)))
        assertEquals(2, TorrentBrowser.filter(options, TorrentFilter(maxSizeGb = 5.0)).size)
    }

    @Test fun sortsBySeedersQualityAndSize() {
        assertEquals(300, TorrentBrowser.sort(options, TorrentSort.SEEDERS).first().stream.seeders)
        assertEquals(2160, TorrentBrowser.sort(options, TorrentSort.QUALITY).first().resolution)
        assertEquals(720, TorrentBrowser.sort(options, TorrentSort.SIZE).first().resolution)
    }

    @Test fun sameTorrentFromTwoAddonsShowsOnce() {
        val a = TorrentBrowser.describe(stream("X.1080p.WEB-DL", 5, 1.0, 1080, hash = "a".repeat(40)), "MediaFusion")
        val b = TorrentBrowser.describe(stream("X.1080p.WEB-DL", 50, 1.0, 1080, hash = "a".repeat(40)), "Torrentio")
        val merged = TorrentBrowser.merge(listOf(a, b))
        assertEquals(1, merged.size)
        assertEquals(50, merged.first().stream.seeders)
    }

    @Test fun serverCarriesTheTorrentUri() {
        val server = TorrentBrowser.toServer(options[1])
        assertTrue(TorrentSpec.isTorrentUri(server.src))
        assertTrue(server.name.startsWith("Torrent 1080p BluRay"))
    }
}
