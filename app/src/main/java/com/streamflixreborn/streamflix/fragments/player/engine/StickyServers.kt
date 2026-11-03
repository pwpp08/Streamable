package com.streamflixreborn.streamflix.fragments.player.engine

import android.content.Context
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider
import org.json.JSONObject

/**
 * Remembers which server last played a title successfully, so it can be tried first next time.
 * Episodes share the entry of their show.
 */
object StickyServers {

    private const val PREFS = "player_sticky_servers"
    private const val KEY_MAP = "map"
    private const val MAX_ENTRIES = 300

    fun key(provider: Provider?, videoType: Video.Type): String? {
        val providerName = provider?.name ?: return null
        val titleId = when (videoType) {
            is Video.Type.Movie -> "movie:${videoType.id}"
            is Video.Type.Episode -> "tv:${videoType.tvShow.id}"
        }
        return "$providerName|$titleId"
    }

    fun get(context: Context, key: String?): String? {
        key ?: return null
        return read(context).optString(key).takeIf { it.isNotEmpty() }
    }

    @Synchronized
    fun put(context: Context, key: String?, serverName: String) {
        key ?: return
        val map = read(context)
        if (map.optString(key) == serverName) return
        map.remove(key)
        map.put(key, serverName)
        // JSONObject keeps insertion order on Android, so the oldest entries come first.
        while (map.length() > MAX_ENTRIES) {
            val oldest = map.keys().asSequence().firstOrNull() ?: break
            map.remove(oldest)
        }
        prefs(context).edit().putString(KEY_MAP, map.toString()).apply()
    }

    private fun read(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString(KEY_MAP, null) ?: "{}") }.getOrElse { JSONObject() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
