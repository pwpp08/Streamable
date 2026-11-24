package com.streamflixreborn.streamflix.fragments.player.engine

import android.os.SystemClock
import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.torrent.StremioAddons
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Gets a title ready before anyone presses play: the server list (with PenguPlay's answer) and the
 * best servers already resolved. The player picks this up through [NextEpisodePrefetch], so play
 * starts without the list/resolve wait. Called from the film page and the Continue Watching row.
 */
object PlayWarmup {
    private const val TAG = "PlayWarmup"
    private const val AGAIN_AFTER_MS = 2 * 60_000L
    private const val ADDON_WAIT_MS = 8_000L
    private const val RESOLVE_WAIT_MS = 12_000L
    private const val RESOLVE_TOP = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = ConcurrentHashMap<String, Long>()

    fun warm(type: Video.Type) {
        val provider = UserPreferences.currentProvider ?: return
        val id = when (type) {
            is Video.Type.Movie -> type.id
            is Video.Type.Episode -> type.id
        }
        if (id.isBlank()) return
        val key = "${provider.name}|$id"
        val now = SystemClock.elapsedRealtime()
        if (NextEpisodePrefetch.isPrepared(id, provider.name)) return
        val previous = started[key]
        if (previous != null && now - previous < AGAIN_AFTER_MS) return
        started[key] = now
        if (started.size > 64) started.entries.removeIf { now - it.value > AGAIN_AFTER_MS }

        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            var servers = runCatching { provider.getServers(id, type) }.getOrNull().orEmpty()
            if (servers.isEmpty()) return@launch
            // The list is returned without waiting long for PenguPlay; here there is time to wait for it.
            if (StremioAddons.configured().isNotEmpty() && servers.none { StremioAddons.isAddonServer(it) }) {
                val addon = runCatching { withTimeoutOrNull(ADDON_WAIT_MS) { StremioAddons.Servers().servers(type) } }
                    .getOrNull().orEmpty()
                if (addon.isNotEmpty()) servers = (addon + servers).distinctBy { it.id }
            }
            val sticky = StickyServers.get(StreamFlixApp.instance, StickyServers.key(provider, type))
            val order = ServerRanker.rank(servers, stickyName = sticky)
            val prepared = coroutineScope {
                order.filterNot { ServerRanker.isTorrentServer(it.name) }.take(RESOLVE_TOP).map { server ->
                    async {
                        val video = runCatching { withTimeoutOrNull(RESOLVE_WAIT_MS) { provider.getVideo(server) } }.getOrNull()
                        if (video != null && video.source.isNotEmpty()) server.id to video else null
                    }
                }.awaitAll().filterNotNull().toMap()
            }
            NextEpisodePrefetch.put(NextEpisodePrefetch.Entry(id, provider.name, servers, prepared))
            Log.d(TAG, "ready in ${SystemClock.elapsedRealtime() - t0} ms: ${servers.size} servers, ${prepared.size} resolved (${order.firstOrNull()?.name})")
        }
    }
}
