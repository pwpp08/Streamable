package com.streamflixreborn.streamflix.utils.health

import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists the latest [ServerHealth] results as JSON in `filesDir/provider_health.json`.
 *
 * Thread-safe; reads are served from memory after the first load.
 */
object HealthStore {

    private const val TAG = "HealthStore"
    private const val FILE_NAME = "provider_health.json"
    private const val FORMAT_VERSION = 1

    /** Results older than this are ignored by [scoreFor]. */
    const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    @Serializable
    private data class Snapshot(
        val version: Int = FORMAT_VERSION,
        val results: List<ServerHealth> = emptyList(),
        /** Consecutive health runs a server failed in, by lower-case server name. */
        val streaks: Map<String, Int> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private var cache: List<ServerHealth>? = null
    private var streaks: Map<String, Int> = emptyMap()

    /** A server that failed this many health runs in a row is skipped when playing. */
    const val DEAD_AFTER_RUNS = 2

    /** Overridable for tests; defaults to the app's `filesDir`. */
    internal var fileProvider: () -> File = { File(StreamFlixApp.instance.filesDir, FILE_NAME) }
        set(value) {
            synchronized(lock) {
                field = value
                cache = null
            }
        }

    /** All stored results, in the order they were saved. */
    fun latest(): List<ServerHealth> = synchronized(lock) { load() }

    /**
     * Stores [results]. Everything previously stored for a provider that appears in [results] is
     * replaced; other providers keep their old results, unless [knownProviders] is given, in which
     * case results of providers outside it (e.g. removed sources) are dropped.
     */
    fun save(results: List<ServerHealth>, knownProviders: Set<String>? = null) {
        synchronized(lock) {
            val providers = results.mapTo(HashSet()) { it.provider }
            val merged = load().filterNot { it.provider in providers || (knownProviders != null && it.provider !in knownProviders) } + results
            cache = merged
            streaks = nextStreaks(streaks, results)
            write(merged)
        }
    }

    /** True when [serverName] failed the last [DEAD_AFTER_RUNS] health runs, so playback should not wait for it. */
    fun isDead(serverName: String): Boolean = synchronized(lock) {
        load()
        (streaks[serverName.trim().lowercase()] ?: 0) >= DEAD_AFTER_RUNS
    }

    /**
     * Failure streaks after one more run: a server that had at least one working result in
     * [run] resets to 0, one that only failed counts up. Provider-level and "title not found"
     * results say nothing about a server and are ignored.
     */
    internal fun nextStreaks(previous: Map<String, Int>, run: List<ServerHealth>): Map<String, Int> {
        val byServer = run
            .filter { !it.isProviderLevel && !it.isUnknown }
            .groupBy { it.serverName.trim().lowercase() }
        val next = previous.toMutableMap()
        for ((name, results) in byServer) {
            next[name] = if (results.any { it.ok }) 0 else (previous[name] ?: 0) + 1
        }
        return next
    }

    fun clear() {
        synchronized(lock) {
            cache = emptyList()
            streaks = emptyMap()
            runCatching { fileProvider().delete() }
        }
    }

    /**
     * Health score of a server, for ranking servers before playback. Higher is better.
     *
     * Looks at every stored result whose `serverName` equals [serverName] (case-insensitive,
     * trimmed), across providers and probes, ignoring results older than [MAX_AGE_MS] and
     * "title not found" results. Each result scores:
     * - failed: 0
     * - working: 100, plus a quality bonus (2160p+: 40, 1080p: 30, 720p: 20, lower: 10,
     *   unknown: 15), minus a latency penalty of 1 point per 500 ms (at most 30).
     *
     * The returned score is the average, in the range 0..140.
     *
     * @return the score, or null when the server has no usable result (never checked, or stale).
     */
    fun scoreFor(serverName: String): Int? {
        val wanted = serverName.trim()
        if (wanted.isEmpty()) return null
        val now = System.currentTimeMillis()
        val matches = latest().filter {
            !it.isProviderLevel && !it.isUnknown &&
                it.serverName.trim().equals(wanted, ignoreCase = true) &&
                now - it.checkedAt <= MAX_AGE_MS
        }
        if (matches.isEmpty()) return null
        return matches.map(::score).average().toInt()
    }

    internal fun score(result: ServerHealth): Int {
        if (!result.ok) return 0
        val quality = when (val height = result.maxHeight) {
            null -> 15
            else -> when {
                height >= 2160 -> 40
                height >= 1080 -> 30
                height >= 720 -> 20
                else -> 10
            }
        }
        val latencyPenalty = ((result.latencyMs ?: 0L) / 500L).toInt().coerceIn(0, 30)
        return 100 + quality - latencyPenalty
    }

    private fun load(): List<ServerHealth> {
        cache?.let { return it }
        val loaded = runCatching {
            val file = fileProvider()
            if (!file.isFile) emptyList()
            else json.decodeFromString(Snapshot.serializer(), file.readText()).also { streaks = it.streaks }.results
        }.onFailure { warn("Unreadable health file, starting fresh", it) }
            .getOrDefault(emptyList())
        cache = loaded
        return loaded
    }

    private fun write(results: List<ServerHealth>) {
        runCatching {
            val file = fileProvider()
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(Snapshot.serializer(), Snapshot(results = results, streaks = streaks)))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { warn("Could not write health file", it) }
    }

    private fun warn(message: String, error: Throwable) {
        runCatching { Log.w(TAG, message, error) }
    }
}
