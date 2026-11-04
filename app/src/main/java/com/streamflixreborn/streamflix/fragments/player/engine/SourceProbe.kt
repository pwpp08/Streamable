package com.streamflixreborn.streamflix.fragments.player.engine

import android.os.SystemClock
import android.util.Base64
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Measures what a resolved stream can really deliver before it is played: the sharpest rung that
 * fits the screen and how fast a sample of its media arrives. Servers differ a lot (and from
 * minute to minute), so choosing by measured speed beats choosing by a fixed order.
 */
object SourceProbe {

    /** [speedMbps] is null when the sample could not be read. */
    data class Result(val height: Int, val rungMbps: Double, val speedMbps: Double?) {
        /** How many times over the link covers the rung bitrate. */
        val headroom: Double? get() = speedMbps?.let { if (rungMbps > 0) it / rungMbps else null }
        val sustainable: Boolean get() = (headroom ?: 0.0) >= MIN_HEADROOM
    }

    data class Variant(val bandwidth: Long, val width: Int, val height: Int, val uri: String)

    const val MIN_HEADROOM = 1.4
    private const val SAMPLE_BYTES = 384 * 1024L
    private const val PLAYLIST_BYTES = 64 * 1024L

    private val bandwidthRegex = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
    private val resolutionRegex = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)

    private val client by lazy {
        NetworkClient.default.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
    }

    fun parseVariants(master: String): List<Variant> {
        val lines = master.lines().map { it.trim() }
        return lines.indices.mapNotNull { i ->
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) return@mapNotNull null
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@mapNotNull null
            val bandwidth = bandwidthRegex.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
            val res = resolutionRegex.find(line)
            Variant(
                bandwidth,
                res?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                res?.groupValues?.get(2)?.toIntOrNull() ?: 0,
                uri,
            )
        }
    }

    /** The highest-bitrate variant whose picture fits [maxPixelHeight]; the lowest one if none does. */
    fun chooseVariant(variants: List<Variant>, maxPixelHeight: Int): Variant? =
        variants.filter { it.height in 1..maxPixelHeight }.maxByOrNull { it.bandwidth }
            ?: variants.filter { it.height == 0 }.maxByOrNull { it.bandwidth }
            ?: variants.minByOrNull { it.bandwidth }

    fun segmentUris(media: String): List<String> =
        media.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /**
     * Picks the best measured stream: full HD first, then one the link sustains, then the fastest.
     * When nothing could be measured the first (best-ranked) item wins.
     */
    fun <T> pickBest(items: List<Pair<T, Result?>>): T? {
        val measured = items.filter { it.second?.speedMbps != null }
        if (measured.isEmpty()) return items.firstOrNull()?.first
        return measured.maxWithOrNull(
            compareBy<Pair<T, Result?>> { if (it.second!!.height >= 1000) 1 else 0 }
                .thenBy { if (it.second!!.sustainable) 1 else 0 }
                .thenBy { it.second!!.speedMbps!! },
        )?.first
    }

    /** Null when the stream is not an HLS playlist we can read within the time limit. */
    suspend fun measure(video: Video, maxPixelHeight: Int = 1088, timeoutMs: Long = 3_500L): Result? =
        withTimeoutOrNull(timeoutMs) { runCatching { measureNow(video, maxPixelHeight) }.getOrNull() }

    private suspend fun measureNow(video: Video, maxPixelHeight: Int): Result? = runInterruptible(Dispatchers.IO) {
        val source = video.source
        if (!source.startsWith("http", true) && !source.startsWith("data:", true)) return@runInterruptible null
        val base = if (source.startsWith("http", true)) source.toHttpUrlOrNull() else null
        val master = text(video, source) ?: return@runInterruptible null
        if (!master.trimStart().startsWith("#EXTM3U")) return@runInterruptible null

        val variant = parseVariants(master).takeIf { it.isNotEmpty() }?.let { chooseVariant(it, maxPixelHeight) }
        val mediaUrl: String
        val media: String
        if (variant != null) {
            mediaUrl = base?.resolve(variant.uri)?.toString() ?: variant.uri
            media = text(video, mediaUrl) ?: return@runInterruptible null
        } else {
            mediaUrl = source
            media = master
        }
        val segments = segmentUris(media)
        // The very first segment is often a short intro; sample the third when there is one.
        val segment = segments.getOrNull(2) ?: segments.firstOrNull() ?: return@runInterruptible null
        val segmentUrl = mediaUrl.toHttpUrlOrNull()?.resolve(segment)?.toString() ?: return@runInterruptible null

        val builder = Request.Builder().url(segmentUrl).header("Range", "bytes=0-${SAMPLE_BYTES - 1}")
        video.headers?.forEach { (k, v) -> runCatching { builder.header(k, v) } }
        val started = SystemClock.elapsedRealtime()
        val bytes = client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return@use 0L
            val body = response.body ?: return@use 0L
            var total = 0L
            val buffer = ByteArray(32 * 1024)
            val stream = body.byteStream()
            while (total < SAMPLE_BYTES) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        }
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        val speed = if (bytes > 32 * 1024 && seconds > 0) bytes * 8 / 1e6 / seconds else null
        Result(
            height = variant?.height?.takeIf { it > 0 } ?: 0,
            rungMbps = (variant?.bandwidth ?: 0L) / 1e6,
            speedMbps = speed,
        )
    }

    private fun text(video: Video, url: String): String? {
        if (url.startsWith("data:", true)) {
            val comma = url.indexOf(',')
            if (comma < 0) return null
            return runCatching {
                if (url.substring(5, comma).endsWith(";base64", true)) {
                    String(Base64.decode(url.substring(comma + 1), Base64.DEFAULT), Charsets.UTF_8)
                } else {
                    java.net.URLDecoder.decode(url.substring(comma + 1), "UTF-8")
                }
            }.getOrNull()
        }
        val builder = Request.Builder().url(url)
        video.headers?.forEach { (k, v) -> runCatching { builder.header(k, v) } }
        return client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.source()?.let { it.request(PLAYLIST_BYTES); it.buffer.clone().readUtf8() }
        }
    }
}
