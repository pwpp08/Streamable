package com.streamflixreborn.streamflix.utils.health

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthStreakTest {

    private fun result(server: String, ok: Boolean, error: String? = null, probe: String = ServerHealth.PROBE_MOVIE) =
        ServerHealth("TMDb (en)", server, ok, error = error, checkedAt = 1L, probe = probe)

    @Test
    fun failureCountsUpAndSuccessResets() {
        var streaks = HealthStore.nextStreaks(emptyMap(), listOf(result("A", false, "timeout")))
        assertEquals(1, streaks["a"])
        streaks = HealthStore.nextStreaks(streaks, listOf(result("A", false, "timeout")))
        assertEquals(2, streaks["a"])
        streaks = HealthStore.nextStreaks(streaks, listOf(result("A", true)))
        assertEquals(0, streaks["a"])
    }

    @Test
    fun oneWorkingProbeKeepsServerAlive() {
        val streaks = HealthStore.nextStreaks(
            mapOf("a" to 1),
            listOf(result("A", false, "403"), result("A", true, probe = ServerHealth.PROBE_EPISODE)),
        )
        assertEquals(0, streaks["a"])
    }

    @Test
    fun titleNotFoundAndProviderFailuresAreIgnored() {
        val streaks = HealthStore.nextStreaks(
            mapOf("a" to 1),
            listOf(
                result("A", false, ServerHealth.ERROR_NO_MATCH),
                result(ServerHealth.PROVIDER_LEVEL, false, "search failed"),
            ),
        )
        assertEquals(mapOf("a" to 1), streaks)
    }
}
