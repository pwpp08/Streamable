package com.streamflixreborn.streamflix.downloads

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * HTTP headers of each download's stream, keyed by download id, so a download resumed after
 * process death sends the same Referer/Origin/cookies as when it started.
 */
object DownloadHeaderStore {

    private const val TAG = "DownloadHeaderStore"
    private const val FILE = "headers.json"

    @Serializable
    data class Entry(
        val headers: Map<String, String> = emptyMap(),
        val maintainToken: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Entry.serializer())

    @Volatile
    private var cache: MutableMap<String, Entry>? = null

    private fun file(context: Context) = File(DownloadUtil.rootDir(context), FILE)

    @Synchronized
    private fun load(context: Context): MutableMap<String, Entry> {
        cache?.let { return it }
        val loaded = runCatching {
            val f = file(context)
            if (f.exists()) json.decodeFromString(serializer, f.readText()).toMutableMap() else mutableMapOf()
        }.getOrElse {
            Log.w(TAG, "header store unreadable, starting empty", it)
            mutableMapOf()
        }
        cache = loaded
        return loaded
    }

    @Synchronized
    private fun persist(context: Context, map: Map<String, Entry>) {
        runCatching {
            val f = file(context)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(json.encodeToString(serializer, map))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }.onFailure { Log.w(TAG, "saving headers failed", it) }
    }

    @Synchronized
    fun get(context: Context, id: String): Entry? = load(context)[id]

    @Synchronized
    fun put(context: Context, id: String, entry: Entry) {
        val map = load(context)
        map[id] = entry
        persist(context, map)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val map = load(context)
        if (map.remove(id) != null) persist(context, map)
    }
}
