package com.streamflixreborn.streamflix.utils.health

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HealthStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("health").toFile()
        HealthStore.fileProvider = { File(dir, "provider_health.json") }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun result(provider: String, server: String, ok: Boolean, height: Int? = null, latency: Long = 0, error: String? = null) =
        ServerHealth(provider, server, ok, latency, height, error, System.currentTimeMillis())

    @Test
    fun `save persists and replaces per provider`() {
        HealthStore.save(listOf(result("A", "VidCloud", true, 1080), result("B", "Voe", false)))
        HealthStore.save(listOf(result("A", "UpCloud", true, 720)))

        HealthStore.fileProvider = { File(dir, "provider_health.json") } // drops the memory cache
        val stored = HealthStore.latest()
        assertEquals(listOf("Voe", "UpCloud"), stored.map { it.serverName })
    }

    @Test
    fun `score prefers working high quality fast servers`() {
        HealthStore.save(
            listOf(
                result("A", "Fast1080", true, 1080, latency = 400),
                result("A", "Slow720", true, 720, latency = 9_000),
                result("A", "Broken", false),
                result("A", "NoMatch", false, error = ServerHealth.ERROR_NO_MATCH),
            ),
        )
        val fast = HealthStore.scoreFor("fast1080 ")!!
        val slow = HealthStore.scoreFor("Slow720")!!
        assertTrue(fast > slow)
        assertEquals(0, HealthStore.scoreFor("Broken"))
        assertNull(HealthStore.scoreFor("NoMatch"))
        assertNull(HealthStore.scoreFor("Unknown"))
    }

    @Test
    fun `stale results are ignored`() {
        val old = result("A", "Old", true).copy(checkedAt = System.currentTimeMillis() - HealthStore.MAX_AGE_MS - 1)
        HealthStore.save(listOf(old))
        assertNull(HealthStore.scoreFor("Old"))
    }

    @Test
    fun `corrupt file reads as empty`() {
        File(dir, "provider_health.json").writeText("{not json")
        HealthStore.fileProvider = { File(dir, "provider_health.json") }
        assertTrue(HealthStore.latest().isEmpty())
    }

    @Test
    fun `title matching`() {
        val titles = listOf("Inception: The Cobol Job", "Inception (2010)", "Inception")
        assertEquals("Inception", ProviderHealthChecker.bestMatch(titles, "Inception") { it })
        assertEquals("Inception: The Cobol Job", ProviderHealthChecker.bestMatch(titles.take(2), "Inception") { it })
        assertNull(ProviderHealthChecker.bestMatch(listOf("Better Call Saul"), "Breaking Bad") { it })
    }
}
