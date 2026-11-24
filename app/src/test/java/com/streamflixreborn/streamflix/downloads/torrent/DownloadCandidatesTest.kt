package com.streamflixreborn.streamflix.downloads.torrent

import com.streamflixreborn.streamflix.torrent.TorrentStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCandidatesTest {

    private fun stream(
        hash: Char,
        release: String,
        height: Int,
        seeders: Int = 50,
        gb: Double = 3.0,
        languages: String = "",
        site: String = "Site",
    ) = TorrentStream(
        infoHash = hash.toString().repeat(40),
        fileIdx = null,
        release = release,
        fileName = null,
        seeders = seeders,
        sizeBytes = (gb * (1L shl 30)).toLong(),
        site = site,
        languages = languages,
        height = height,
        trackers = emptyList(),
    )

    @Test
    fun `one pick per resolution, best resolution first`() {
        val picks = DownloadCandidates.pick(
            listOf(
                stream('a', "Film.2020.720p.WEB-DL.x264", 720, seeders = 30),
                stream('b', "Film.2020.1080p.WEB-DL.x264", 1080, seeders = 200),
                stream('c', "Film.2020.1080p.WEB-DL.x264.OTHER", 1080, seeders = 20),
                stream('d', "Film.2020.480p.DVDRip", 480, seeders = 10, gb = 1.0),
            ),
        )
        assertEquals(listOf(1080, 720, 480), picks.map { it.height })
        assertEquals("b".repeat(40), picks.first().infoHash)
    }

    @Test
    fun `cams, dead torrents, burned-in subtitles and huge files are dropped`() {
        val picks = DownloadCandidates.pick(
            listOf(
                stream('a', "Film.2020.1080p.HDCAM", 1080),
                stream('b', "Film.2020.1080p.WEB-DL", 1080, seeders = 1),
                stream('c', "Film.2020.1080p.HC.KOR.SUBS", 1080),
                stream('d', "Film.2020.2160p.REMUX", 2160, gb = 60.0),
                stream('e', "Film.2020.720p.BluRay", 720),
            ),
        )
        assertEquals(listOf("e".repeat(40)), picks.map { it.infoHash })
    }

    @Test
    fun `4k is offered when it is a sane size`() {
        val picks = DownloadCandidates.pick(listOf(stream('a', "Film.2020.2160p.WEB-DL.x265", 2160, gb = 12.0)))
        assertEquals(listOf(2160), picks.map { it.height })
    }

    @Test
    fun `x264 releases beat equally seeded others`() {
        val picks = DownloadCandidates.pick(
            listOf(
                stream('a', "Film.2020.1080p.WEB-DL.DDP5.1.AV1", 1080, seeders = 50),
                stream('b', "Film.2020.1080p.WEB-DL.x264", 1080, seeders = 50),
            ),
        )
        assertEquals("b".repeat(40), picks.single().infoHash)
    }

    @Test
    fun `subtitle names are ranked by shared release words`() {
        val release = "Film.2020.1080p.WEB-DL.x264-GRP"
        assertTrue(
            DownloadSubtitles.similarity(release, "Film.2020.1080p.WEB-DL.x264-GRP.srt") >
                DownloadSubtitles.similarity(release, "Film.2020.BluRay.srt"),
        )
    }
}
