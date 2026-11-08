package com.streamflixreborn.streamflix.utils.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistParserTest {

    private val master = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d001e,mp4a.40.2"
        360/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,FRAME-RATE=23.976
        1080/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
        720/index.m3u8
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=90000,RESOLUTION=3840x2160,URI="iframe.m3u8"
    """.trimIndent()

    @Test
    fun `master playlist yields highest stream variant`() {
        assertTrue(HlsPlaylistParser.isPlaylist(master))
        assertTrue(HlsPlaylistParser.isMasterPlaylist(master))
        assertEquals(3, HlsPlaylistParser.variants(master).size)
        assertEquals(1080, HlsPlaylistParser.maxHeight(master))
    }

    @Test
    fun `media playlist has unknown height`() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertTrue(HlsPlaylistParser.isPlaylist(media))
        assertFalse(HlsPlaylistParser.isMasterPlaylist(media))
        assertNull(HlsPlaylistParser.maxHeight(media))
    }

    @Test
    fun `master playlist without resolution has unknown height`() {
        val noRes = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nlow.m3u8\n"
        assertNull(HlsPlaylistParser.maxHeight(noRes))
    }

    @Test
    fun `the quality name ignores the letterbox`() {
        assertEquals(1080, HlsPlaylistParser.displayHeight(1920, 816))
        assertEquals(1080, HlsPlaylistParser.displayHeight(1916, 800))
        assertEquals(1080, HlsPlaylistParser.displayHeight(2592, 1080))
        assertEquals(720, HlsPlaylistParser.displayHeight(1280, 534))
        assertEquals(720, HlsPlaylistParser.displayHeight(1280, 720))
        assertEquals(480, HlsPlaylistParser.displayHeight(0, 480))
    }

    @Test
    fun `letterboxed and lowercase attributes are handled`() {
        val text = "﻿#EXTM3U\r\n#ext-x-stream-inf:bandwidth=1,resolution=1920x800\r\na.m3u8\r\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=2,RESOLUTION=1280x534\r\nb.m3u8\r\n"
        assertTrue(HlsPlaylistParser.isPlaylist(text))
        assertEquals(1080, HlsPlaylistParser.maxHeight(text))
        assertEquals(720, HlsPlaylistParser.qualityHeight(1280, 534))
    }

    @Test
    fun `non playlist text is rejected`() {
        assertFalse(HlsPlaylistParser.isPlaylist("<html>blocked</html>"))
        assertNull(HlsPlaylistParser.maxHeight("<html>RESOLUTION=1920x1080</html>"))
    }

    @Test
    fun `labels`() {
        assertEquals("1080p", HlsPlaylistParser.label(1080))
        assertEquals("?", HlsPlaylistParser.label(null))
    }
}
