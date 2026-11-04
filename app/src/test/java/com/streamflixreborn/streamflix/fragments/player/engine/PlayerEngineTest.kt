package com.streamflixreborn.streamflix.fragments.player.engine

import com.streamflixreborn.streamflix.models.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerEngineTest {

    private fun servers(vararg names: String) = names.map { Video.Server(id = it.lowercase(), name = it) }

    @Test
    fun `ranking keeps original order without hints`() {
        val ranked = ServerRanker.rank(servers("A", "B", "C"), score = { null })
        assertEquals(listOf("A", "B", "C"), ranked.map { it.name })
    }

    @Test
    fun `ranking puts preferred, then sticky, then healthy servers first`() {
        val scores = mapOf("C" to 90, "D" to 10)
        val ranked = ServerRanker.rank(
            servers("A", "B", "C", "D", "E"),
            preferredName = "e",
            stickyName = "B",
            score = { scores[it] },
        )
        assertEquals(listOf("E", "B", "C", "A", "D"), ranked.map { it.name })
    }

    @Test
    fun `ranking survives a throwing health lambda`() {
        val ranked = ServerRanker.rank(servers("A", "B"), score = { error("boom") })
        assertEquals(listOf("A", "B"), ranked.map { it.name })
    }

    @Test
    fun `retry backoff doubles up to four seconds`() {
        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 4000L, 4000L), (1..6).map(RetryingLoadErrorPolicy::backoffMs))
    }

    @Test
    fun `video size cap follows display, max resolution and data saver`() {
        assertEquals(3305 to 1102, TrackPreferences.videoSizeCap(1920, 1080, 0, false))
        assertEquals(2203 to 734, TrackPreferences.videoSizeCap(1920, 1080, 720, false))
        assertEquals(2203 to 734, TrackPreferences.videoSizeCap(3840, 2160, 0, true))
        assertEquals(6610 to 2203, TrackPreferences.videoSizeCap(3840, 2160, 0, false))
    }

    @Test
    fun `frame rate matching picks an exact multiple of the same resolution`() {
        val m60 = FrameRateMatcher.ModeInfo(1, 1920, 1080, 60f)
        val m24 = FrameRateMatcher.ModeInfo(2, 1920, 1080, 24f)
        val m50 = FrameRateMatcher.ModeInfo(3, 1920, 1080, 50f)
        val m4k24 = FrameRateMatcher.ModeInfo(4, 3840, 2160, 24f)
        val modes = listOf(m60, m24, m50, m4k24)
        assertEquals(m24, FrameRateMatcher.pickMode(modes, m60, 24f))
        assertEquals(m50, FrameRateMatcher.pickMode(modes, m60, 25f))
        assertEquals(m60, FrameRateMatcher.pickMode(modes, m60, 30f))
        assertNull(FrameRateMatcher.pickMode(listOf(m60), m60, 23.976f))
    }

    @Test
    fun `a server that played in full HD outranks one that stops at 720p`() {
        val heights = mapOf("Fast" to 720, "Sharp" to 1080)
        val ranked = ServerRanker.rank(
            servers("Fast", "Sharp", "Unknown"),
            score = { 80 },
            height = { heights[it] },
        )
        assertEquals(listOf("Sharp", "Unknown", "Fast"), ranked.map { it.name })
    }

    @Test
    fun `sticky server loses its lead when it is low-res and another server plays in full HD`() {
        val heights = mapOf("Sticky" to 720, "Sharp" to 1080)
        val ranked = ServerRanker.rank(
            servers("Sticky", "Sharp"),
            stickyName = "Sticky",
            score = { 50 },
            height = { heights[it] },
        )
        assertEquals("Sharp", ranked.first().name)
    }

    @Test
    fun `sticky server keeps its lead when nothing else is known to be full HD`() {
        val ranked = ServerRanker.rank(
            servers("A", "Sticky"),
            stickyName = "Sticky",
            score = { 50 },
            height = { if (it == "Sticky") 720 else null },
        )
        assertEquals("Sticky", ranked.first().name)
    }

    @Test
    fun `quality memory keeps the best recent height and expires old entries`() {
        val now = 1_000_000_000_000L
        assertEquals(1080, QualityMemory.decode("1080|$now", now))
        assertNull(QualityMemory.decode("1080|${now - 15L * 24 * 60 * 60 * 1000}", now))
        assertNull(QualityMemory.decode("garbage", now))
        assertNull(QualityMemory.decode(null, now))
    }
}
