package com.streamflixreborn.streamflix.fragments.player.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceProbeTest {
    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=16186248,RESOLUTION=3840x1600
        r2160.m3u8
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=6200000,RESOLUTION=2592x1080
        r1080.m3u8
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=3100000,RESOLUTION=1728x720
        r720.m3u8
    """.trimIndent()

    @Test
    fun widescreen1080RungIsChosenAndTheFourKOneIsNot() {
        val chosen = SourceProbe.chooseVariant(SourceProbe.parseVariants(master), 1088)
        assertEquals("r1080.m3u8", chosen?.uri)
    }

    @Test
    fun fastFullHdBeatsSlowFullHdAndSlowFullHdBeatsFastLowRes() {
        val fast = SourceProbe.Result(1080, 6.2, 14.0)
        val slow = SourceProbe.Result(1080, 6.2, 7.0)
        val lowRes = SourceProbe.Result(720, 3.1, 30.0)
        assertEquals("fast", SourceProbe.pickBest(listOf("slow" to slow, "fast" to fast, "low" to lowRes)))
        assertEquals("slow", SourceProbe.pickBest(listOf("low" to lowRes, "slow" to slow)))
        assertEquals("first", SourceProbe.pickBest(listOf("first" to null, "second" to null)))
        val unstated = SourceProbe.Result(0, 0.0, 5.0)
        assertEquals("unstated", SourceProbe.pickBest(listOf("low" to lowRes, "unstated" to unstated)))
        assertEquals("fast", SourceProbe.pickBest(listOf("unstated" to unstated, "fast" to fast)))
        val sd = SourceProbe.Result(480, 1.0, 30.0)
        val hd = SourceProbe.Result(720, 1.0, 10.0)
        assertEquals("hd", SourceProbe.pickBest(listOf("sd" to sd, "hd" to hd)))
    }

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun theRealPictureSizeIsReadFromTheStreamHeader() {
        val noise = ByteArray(40) { 0x47 }
        val startCode = byteArrayOf(0, 0, 1)
        assertEquals(1080, SourceProbe.pictureHeight(noise + startCode + bytes("67640028ace501e0089f95") + noise))
        // 1280x544 is a wide 720p film.
        assertEquals(720, SourceProbe.pictureHeight(noise + startCode + bytes("67640028ace50140117f40")))
        assertEquals(0, SourceProbe.pictureHeight(noise))
    }
}
