package com.streamflixreborn.streamflix.utils.health

import kotlinx.serialization.Serializable

/**
 * Result of probing one server of one provider.
 *
 * @property provider provider name as returned by `Provider.name` (e.g. "NetPrime", "TMDb (en)").
 * @property serverName `Video.Server.name` of the probed server, or [PROVIDER_LEVEL] when the
 *   provider failed before any server could be probed (search, lookup or `getServers` failure).
 * @property ok true when `getVideo` returned a source and the stream answered.
 * @property latencyMs wall time of `getVideo` plus the stream request; null when not measured.
 * @property maxHeight best quality class in pixels (1080, 720, ...); null when unknown
 *   (media playlists without RESOLUTION, MP4, DASH, failures).
 * @property error short failure reason; [ERROR_NO_MATCH] when the test title was not found.
 * @property checkedAt epoch millis of the check.
 * @property probe which test title produced the result: [PROBE_MOVIE] or [PROBE_EPISODE].
 */
@Serializable
data class ServerHealth(
    val provider: String,
    val serverName: String,
    val ok: Boolean,
    val latencyMs: Long? = null,
    val maxHeight: Int? = null,
    val error: String? = null,
    val checkedAt: Long,
    val probe: String = PROBE_MOVIE,
) {
    val isProviderLevel: Boolean get() = serverName == PROVIDER_LEVEL

    /** The provider did not carry the test title; this says nothing about its servers. */
    val isUnknown: Boolean get() = !ok && error == ERROR_NO_MATCH

    companion object {
        const val PROVIDER_LEVEL = "*"
        const val ERROR_NO_MATCH = "no-match"
        const val PROBE_MOVIE = "movie"
        const val PROBE_EPISODE = "episode"
    }
}
