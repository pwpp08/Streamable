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

    /** Best height this server has actually played at, or null when unknown. */
    @Volatile
    var playedHeight: (serverName: String) -> Int? = { null }

    /** Added to the score of a server that has played in full HD. */
    const val FULL_HD_BONUS = 25

    /** Taken off a server that is known to stop below full HD, so unknown ones get a try. */
    const val LOW_RES_PENALTY = 10

    /** Taken off P2P servers: web servers start faster, so torrents only lead when known to be full HD. */
    const val TORRENT_PENALTY = 15

    fun isTorrentServer(name: String) = name.startsWith("Torrent ", ignoreCase = true)

    fun rank(
        servers: List<Video.Server>,
        preferredName: String? = null,
        stickyName: String? = null,
        score: (String) -> Int? = healthScore,
        height: (String) -> Int? = playedHeight,
    ): List<Video.Server> {
        if (servers.size <= 1) return servers
        val heights = servers.associateWith { runCatching { height(it.name) }.getOrNull() }
        val scores = servers.associateWith { server ->
            val base = runCatching { score(server.name) }.getOrNull() ?: UNKNOWN_SCORE
            val played = heights.getValue(server)
            base + when {
                played == null -> 0
                played >= 1080 -> FULL_HD_BONUS
                else -> -LOW_RES_PENALTY
            } - (if (isTorrentServer(server.name) && (played == null || played < 1080)) TORRENT_PENALTY else 0)
        }
        // The sticky server only keeps its lead when it isn't known to be low-res while another
        // server is known to play in full HD.
        val fullHdExists = heights.values.any { it != null && it >= 1080 }
        val stickyHeight = servers.firstOrNull { it.name.equals(stickyName, ignoreCase = true) }
            ?.let { heights.getValue(it) }
        val stickyUsable = !(fullHdExists && stickyHeight != null && stickyHeight < 1080)
        return servers.withIndex()
            .sortedWith(
                compareBy<IndexedValue<Video.Server>> { if (it.value.name.equals(preferredName, ignoreCase = true)) 0 else 1 }
                    .thenBy { if (stickyUsable && it.value.name.equals(stickyName, ignoreCase = true)) 0 else 1 }
                    .thenByDescending { scores.getValue(it.value) }
                    .thenBy { it.index }
            )
            .map { it.value }
    }
}
