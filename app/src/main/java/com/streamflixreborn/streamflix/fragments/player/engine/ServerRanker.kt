package com.streamflixreborn.streamflix.fragments.player.engine

import com.streamflixreborn.streamflix.models.Video

/**
 * Orders servers for parallel resolution and failover:
 * explicitly preferred server → web servers before torrents → sticky server (last one that played this title) →
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

    /** Taken off a server that stalled in the last half hour. */
    const val STALL_PENALTY = 40

    /** Taken off P2P servers: web servers start faster, so torrents only lead when known to be full HD. */
    const val TORRENT_PENALTY = 15

    fun isTorrentServer(name: String) = name.startsWith("Torrent ", ignoreCase = true)

    /** Videasy nodes answer in ~150 ms with adaptive HLS up to 1080p+, so they start with a head start. */
    fun isVideasy(name: String) = name.startsWith("Videasy ", ignoreCase = true)

    /** Vidzee resolves in 1-2 s; it ranges from 480p to 1080p per title, so its real height decides at start. */
    fun isVidzee(name: String) = name.endsWith("(Vidzee)", ignoreCase = true)

    /** Servers that never go above this height, whatever the title (measured). */
    private val KNOWN_HEIGHT_CAP = mapOf("vixsrc" to 720)

    /** Score for a server nothing is known about yet. */
    const val VIDEASY_PRIOR = 70

    /** Answers quickly, so it is ready to be compared before slow servers that often time out. */
    const val VIDZEE_PRIOR = 65

    /** Dependable 720p: high enough, even after the low-res penalty, to be among the sources compared at start. */
    const val VIXSRC_PRIOR = 70

    fun rank(
        servers: List<Video.Server>,
        preferredName: String? = null,
        stickyName: String? = null,
        score: (String) -> Int? = healthScore,
        height: (String) -> Int? = playedHeight,
    ): List<Video.Server> {
        if (servers.size <= 1) return servers
        val heights = servers.associateWith { server ->
            runCatching { height(server.name) }.getOrNull() ?: KNOWN_HEIGHT_CAP[server.name.lowercase()]
        }
        val scores = servers.associateWith { server ->
            val stalled = runCatching { StallMemory.isRecent(server.name) }.getOrDefault(false)
            val known = runCatching { score(server.name) }.getOrNull()
                ?: when {
                    isVideasy(server.name) -> VIDEASY_PRIOR
                    isVidzee(server.name) -> VIDZEE_PRIOR
                    server.name.equals("VixSrc", ignoreCase = true) -> VIXSRC_PRIOR
                    else -> UNKNOWN_SCORE
                }
            val base = known - if (stalled) STALL_PENALTY else 0
            val played = heights.getValue(server)
            base + when {
                played == null -> 0
                played >= 1080 -> FULL_HD_BONUS
                else -> -LOW_RES_PENALTY
            } - (if (isTorrentServer(server.name) && (played == null || played < 1080)) TORRENT_PENALTY else 0)
        }
        // The sticky server only keeps its lead when it isn't known to be low-res while another
        // server is known to play in full HD.
        val fullHdExists = servers.any { server ->
            !isTorrentServer(server.name) &&
                (isVideasy(server.name) || isVidzee(server.name) || (heights.getValue(server) ?: 0) >= 1080)
        }
        val stickyHeight = servers.firstOrNull { it.name.equals(stickyName, ignoreCase = true) }
            ?.let { heights.getValue(it) }
        // A sticky server only keeps its lead when it is known to deliver full HD, or when no
        // server is expected to (a remembered 720p server must not beat a 1080p one).
        val stickyUsable = if (fullHdExists) stickyHeight != null && stickyHeight >= 1080 else true
        return servers.withIndex()
            .sortedWith(
                compareBy<IndexedValue<Video.Server>> { if (it.value.name.equals(preferredName, ignoreCase = true)) 0 else 1 }
                    // P2P takes ~30 s to start: it only plays first when nothing else is left (the
                    // quality upgrade and failover still reach it).
                    .thenBy { if (isTorrentServer(it.value.name)) 1 else 0 }
                    .thenBy { if (stickyUsable && it.value.name.equals(stickyName, ignoreCase = true)) 0 else 1 }
                    .thenByDescending { scores.getValue(it.value) }
                    .thenBy { it.index }
            )
            .map { it.value }
    }
}
