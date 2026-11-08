package com.streamflixreborn.streamflix.fragments.player.engine

import android.os.SystemClock
import android.util.Base64
import androidx.media3.container.NalUnitUtil
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.health.HlsPlaylistParser
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
    private const val SPS_MAX_BYTES = 256
    private const val HEADER_SCAN_BYTES = 128 * 1024
    private const val HEVC_SPS = 0x42
    private const val H264_SPS = 0x07
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

    /**
     * Quality class of the picture in a piece of an H.264 or HEVC stream, from its SPS header; 0 when
     * [length] bytes of [data] hold none. Playlists without RESOLUTION need this: a bitrate can't
     * tell 1080p HEVC from 720p H.264.
     */
    fun pictureHeight(data: ByteArray, length: Int = data.size): Int {
        var i = 0
        while (i + 5 < length) {
            if (data[i].toInt() != 0 || data[i + 1].toInt() != 0 || data[i + 2].toInt() != 1) {
                i++
                continue
            }
            val header = data[i + 3].toInt() and 0xFF
            val end = minOf(length, i + 3 + SPS_MAX_BYTES)
            val size = runCatching {
                when {
                    header == HEVC_SPS && data[i + 4].toInt() == 1 ->
                        NalUnitUtil.parseH265SpsNalUnit(data, i + 3, end, null).let { it.width to it.height }
                    header and 0x9F == H264_SPS -> NalUnitUtil.parseSpsNalUnit(data, i + 3, end).let { it.width to it.height }
                    else -> null
                }
            }.getOrNull()
            if (size != null && size.first in 160..8192 && size.second in 90..4320) {
                return HlsPlaylistParser.qualityHeight(size.first, size.second)
            }
            i += 3
        }
        return 0
    }

    fun segmentUris(media: String): List<String> =
        media.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /**
     * Picks the best measured stream: full HD first, then one whose height is unknown (it may well be
     * full HD), then known lower ones; within those, one the link sustains, the sharper, the fastest.
     * When nothing could be measured the first (best-ranked) item wins.
     */
    fun <T> pickBest(items: List<Pair<T, Result?>>): T? {
        val measured = items.filter { it.second?.speedMbps != null }
        if (measured.isEmpty()) return items.firstOrNull()?.first
        return measured.maxWithOrNull(
            compareBy<Pair<T, Result?>> { it.second!!.height.let { h -> if (h >= 1000) 2 else if (h == 0) 1 else 0 } }
                .thenBy { if (it.second!!.sustainable) 1 else 0 }
                .thenBy { it.second!!.height }
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
        val head = ByteArray(HEADER_SCAN_BYTES)
        var headSize = 0
        val bytes = client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return@use 0L
            val body = response.body ?: return@use 0L
            var total = 0L
            val buffer = ByteArray(32 * 1024)
            val stream = body.byteStream()
            while (total < SAMPLE_BYTES) {
                val read = stream.read(buffer)
                if (read < 0) break
                minOf(read, head.size - headSize).takeIf { it > 0 }?.let {
                    System.arraycopy(buffer, 0, head, headSize, it)
                    headSize += it
                }
                total += read
            }
            total
        }
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        val speed = if (bytes > 32 * 1024 && seconds > 0) bytes * 8 / 1e6 / seconds else null
        Result(
            height = variant?.height?.takeIf { it > 0 } ?: pictureHeight(head, headSize),
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
