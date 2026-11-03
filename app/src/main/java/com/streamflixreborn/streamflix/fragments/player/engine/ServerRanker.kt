package com.streamflixreborn.streamflix.fragments.player.engine

import com.streamflixreborn.streamflix.models.Video

/**
 * Orders servers for parallel resolution and failover:
 * explicitly preferred server → sticky server (last one that played this title) →
 * health score (higher first) → the provider's original order.
 */
object ServerRanker {

    /** Score used when [healthScore] has no data for a server. */
    const val UNKNOWN_SCORE = 50

    /**
     * Health score in 0..100 for a server name, or null when unknown.
     * TODO(integrator): wire the provider health checker here, e.g. from Application.onCreate:
     *   ServerRanker.healthScore = { name -> ServerHealthStore.score(name) }
     */
    @Volatile
    var healthScore: (serverName: String) -> Int? = { null }

    fun rank(
        servers: List<Video.Server>,
        preferredName: String? = null,
        stickyName: String? = null,
        score: (String) -> Int? = healthScore,
    ): List<Video.Server> {
        if (servers.size <= 1) return servers
        val scores = servers.associateWith { server ->
            runCatching { score(server.name) }.getOrNull() ?: UNKNOWN_SCORE
        }
        return servers.withIndex()
            .sortedWith(
                compareBy<IndexedValue<Video.Server>> { if (it.value.name.equals(preferredName, ignoreCase = true)) 0 else 1 }
                    .thenBy { if (it.value.name.equals(stickyName, ignoreCase = true)) 0 else 1 }
                    .thenByDescending { scores.getValue(it.value) }
                    .thenBy { it.index }
            )
            .map { it.value }
    }
}
