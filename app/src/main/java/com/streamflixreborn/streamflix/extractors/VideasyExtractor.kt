package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import android.util.Log
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.UserPreferences
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Videasy: a family of CDN nodes that answer in ~150 ms with ready-to-play HLS (adaptive 480p to
 * 2160p) and need no WebView. Each node returns a payload obfuscated with a small seeded
 * keystream; [decrypt] reverses it.
 */
class VideasyExtractor : Extractor() {

    override val name = "Videasy"
    override val mainUrl = "https://api.wecollege.net"

    private val origin = "https://vidy.st"

    private val client by lazy {
        NetworkClient.noCookies.newBuilder()
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    fun servers(videoType: Video.Type): List<Video.Server> {
        // The same fields movy.sx sends: real title and year help the node match the right release.
        return NODES.map { node ->
            val url = "$mainUrl/${node.path}/sources".toHttpUrl().newBuilder().apply {
                when (videoType) {
                    is Video.Type.Movie -> {
                        addQueryParameter("title", videoType.title)
                        addQueryParameter("mediaType", "movie")
                        yearOf(videoType.releaseDate)?.let { addQueryParameter("year", it) }
                        addQueryParameter("tmdbId", videoType.id)
                        videoType.imdbId?.takeIf { it.isNotBlank() }?.let { addQueryParameter("imdbId", it) }
                    }
                    is Video.Type.Episode -> {
                        addQueryParameter("title", videoType.tvShow.title)
                        addQueryParameter("mediaType", "tv")
                        yearOf(videoType.tvShow.releaseDate)?.let { addQueryParameter("year", it) }
                        addQueryParameter("tmdbId", videoType.tvShow.id)
                        addQueryParameter("seasonId", videoType.season.number.toString())
                        addQueryParameter("episodeId", videoType.number.toString())
                    }
                }
            }.build()
            Video.Server(
                id = "Videasy ${node.label}",
                name = "Videasy ${node.label}",
                src = url.toString(),
            )
        }
    }

    /**
     * Whether the API answers at all. It sits behind Cloudflare, which has been refusing apps with
     * HTTP 403; listing the nodes then only sends playback to the next server. Asked every few minutes.
     */
    fun isReachable(): Boolean {
        val (ok, at) = reach
        if (System.currentTimeMillis() - at < REACH_TTL_MS) return ok
        val result = runCatching { fetch("$mainUrl/seed?mediaId=550"); true }.getOrElse { e ->
            val msg = e.message.orEmpty()
            !(msg.contains("HTTP 403") || msg.contains("HTTP 503"))
        }
        if (!result) Log.w(TAG, "API refuses requests (blocked); Videasy servers hidden for now")
        reach = result to System.currentTimeMillis()
        return result
    }

    private fun yearOf(date: String?): String? = date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

    override suspend fun extract(link: String): Video {
        val url = link.toHttpUrl()
        val tmdbId = url.queryParameter("tmdbId")?.toLongOrNull() ?: throw Exception("Videasy: missing TMDB id")
        val seed = seedFor(tmdbId)

        val request = url.newBuilder()
            .apply { if (url.queryParameter("title").isNullOrBlank()) setQueryParameter("title", "media") }
            .setQueryParameter("enc", "2")
            .setQueryParameter("seed", seed)
            .build()
        val body = fetch(request.toString())
        val payload = JSONObject(decrypt(body, seed, tmdbId))

        val playlist = payload.optString("playlist")
        val sources = payload.optJSONArray("sources") ?: JSONArray()
        val source = when {
            playlist.startsWith("http") -> playlist
            else -> bestSource(sources) ?: throw Exception("Videasy: no sources")
        }

        val subtitles = payload.optJSONArray("subtitles")?.let { list ->
            (0 until list.length()).mapNotNull { i ->
                val item = list.optJSONObject(i) ?: return@mapNotNull null
                val file = item.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val label = item.optString("language").ifBlank { item.optString("lang") }.ifBlank { "Subtitles" }
                Video.Subtitle(label = label, file = file)
            }
        }.orEmpty()

        Log.d(TAG, "${url.encodedPath}: ${sources.length()} sources, playlist=${playlist.isNotEmpty()}")
        return Video(
            source = source,
            subtitles = subtitles,
            headers = mapOf("Origin" to origin, "Referer" to "$origin/", "User-Agent" to USER_AGENT),
            type = MimeTypes.APPLICATION_M3U8,
        )
    }

    /** The sharpest source up to 1080p (or the user's cap); "Auto" entries are adaptive already. */
    private fun bestSource(sources: JSONArray): String? {
        data class Candidate(val height: Int, val url: String)
        val candidates = (0 until sources.length()).mapNotNull { i ->
            val item = sources.optJSONObject(i) ?: return@mapNotNull null
            val url = item.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val quality = item.optString("quality")
            val height = Regex("(\\d{3,4})").find(quality)?.value?.toIntOrNull()
                ?: if (quality.equals("4k", ignoreCase = true)) 2160 else 0
            Candidate(height, url)
        }
        if (candidates.isEmpty()) return null
        val cap = UserPreferences.maxResolution.takeIf { it > 0 } ?: 1080
        return candidates.filter { it.height in 1..cap }.maxByOrNull { it.height }?.url
            ?: candidates.firstOrNull { it.height == 0 }?.url
            ?: candidates.minByOrNull { it.height }?.url
    }

    private fun seedFor(tmdbId: Long): String {
        seeds[tmdbId]?.takeIf { System.currentTimeMillis() - it.second < SEED_TTL_MS }?.let { return it.first }
        val seed = JSONObject(fetch("$mainUrl/seed?mediaId=$tmdbId")).optString("seed")
        if (seed.isEmpty() || seed.length > 4096) throw Exception("Videasy: bad seed")
        seeds[tmdbId] = seed to System.currentTimeMillis()
        return seed
    }

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url)
            .header("Origin", origin)
            .header("Referer", "$origin/")
            .header("User-Agent", USER_AGENT)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Videasy: HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private class Node(val path: String, val label: String)

    companion object {
        private const val TAG = "VideasyExtractor"
        private const val SEED_TTL_MS = 5 * 60_000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /**
         * The nodes that serve adaptive 1080p+ playlists, fastest first. The "Auto" nodes (seattle,
         * reno, oakland) and vegas are left out: lower quality or erratic, and the API rate-limits
         * (HTTP 429) when too many nodes are asked at once.
         */
        private val NODES = listOf(
            Node("miami", "Miami"),
            Node("boise", "Boise"),
            Node("portland", "Portland"),
            Node("atlanta", "Atlanta"),
            Node("phoenix", "Phoenix"),
        )

        private val seeds = ConcurrentHashMap<Long, Pair<String, Long>>()

        private const val REACH_TTL_MS = 10 * 60_000L
        @Volatile private var reach: Pair<Boolean, Long> = false to 0L

        private const val GOLDEN = -1640531527 // 2654435769u
        private val TABLE = longArrayOf(
            1116352408, 1899447441, 3049323471, 3921009573, 961987163, 1508970993, 2453635748, 2870763221,
            3624381080, 310598401, 607225278, 1426881987, 1925078388, 2162078206, 2614888103, 3248222580,
        ).map { it.toInt() }.toIntArray()

        private fun hash32(input: Int): Int {
            var v = input
            v = v xor (v ushr 16)
            v *= -2048144789 // 2246822507u
            v = v xor (v ushr 13)
            v *= -1028477387 // 3266489909u
            return v xor (v ushr 16)
        }

        private fun seedHash(seed: String): Int {
            var v = -2128831035 // 2166136261u
            for (b in seed.toByteArray(Charsets.UTF_8)) {
                v = (v xor (b.toInt() and 0xFF)) * 16777619
            }
            return hash32(v)
        }

        internal class State(seed: String, key: Int) {
            val words = IntArray(61)
            val assigned = BooleanArray(61)
            var acc: Int

            init {
                var value = hash32(seedHash(seed) xor hash32(key xor GOLDEN))
                for (index in 0 until 8) {
                    if ((index * (index + 1)) and 1 == 0) {
                        val slot = Integer.remainderUnsigned(value, 61)
                        value = Integer.rotateLeft(value + GOLDEN, 7 + (7 and index))
                        words[slot] = value xor hash32(value)
                        assigned[slot] = true
                        value = hash32(value + slot)
                    } else {
                        words[index] = TABLE[15 and index]
                        assigned[index] = true
                    }
                }
                acc = hash32(-1515870811 xor value) // 2779096485u
            }

            fun nextWord(counter: Int): Int {
                val slot = Integer.remainderUnsigned(acc, 61)
                val mask = if (assigned[slot]) -1 else 0
                val value = words[slot] xor (GOLDEN * (counter + 1))
                val mixed = (acc xor value) or (acc and value and mask)
                val left = Integer.rotateLeft(mixed + acc, 31 and slot)
                val right = Integer.rotateLeft(acc, 31 and (slot * 7))
                val next = hash32((left xor right) + GOLDEN)
                words[slot] = next
                assigned[slot] = true
                acc = next
                return next
            }
        }

        /** Reverses the node payload: base64 (either alphabet) -> keystream xor -> "mvm1" + JSON. */
        internal fun decrypt(payload: String, seed: String, tmdbId: Long): String {
            val clean = payload.filter { it != '\r' && it != '\n' }.replace('-', '+').replace('_', '/')
            val data = Base64.decode(clean, Base64.DEFAULT)
            val key = if (tmdbId > 0xFFFFFFFFL) -1 else tmdbId.toInt()
            val state = State(seed, key)
            var counter = 0
            var i = 0
            while (i < data.size) {
                val word = state.nextWord(counter++)
                for (j in 0 until 4) {
                    if (i + j < data.size) {
                        data[i + j] = (data[i + j].toInt() xor ((word ushr (8 * j)) and 0xFF)).toByte()
                    }
                }
                i += 4
            }
            if (data.size < 4 || String(data, 0, 4, Charsets.ISO_8859_1) != "mvm1") {
                throw Exception("Videasy: unexpected payload")
            }
            return String(data, 4, data.size - 4, Charsets.UTF_8)
        }
    }
}
