package com.streamflixreborn.streamflix.downloads.mp4

import com.streamflixreborn.streamflix.downloads.mkv.SubtitleEvent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/** The file is not a plain (non-fragmented) MP4 this muxer can extend. */
class UnsupportedMp4Exception(message: String) : Exception(message)

/**
 * Adds a text subtitle track (`tx3g`, what ffmpeg calls mov_text) to an MP4 without touching the
 * picture or sound: the movie box is rebuilt with one more track, the subtitle samples go in a new
 * data box at the end of the file, and the chunk offsets of the existing tracks are shifted when the
 * movie box moved. Works for "faststart" (moov first) and moov-last files alike.
 */
object Mp4SubtitleMuxer {
    private const val SUBTITLE_TIMESCALE = 1000L
    private const val MAX_MOOV_BYTES = 64 shl 20
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl")

    private class Box(val type: String, val start: Long, val size: Long, val header: Int)

    /**
     * @param language ISO 639-2 code ("eng").
     * Throws [UnsupportedMp4Exception] and leaves no [output] when the file can't be handled.
     */
    fun mux(input: File, output: File, events: List<SubtitleEvent>, language: String = "eng", name: String? = null) {
        val tmp = File(output.parentFile, output.name + ".part")
        try {
            RandomAccessFile(input, "r").use { src -> build(src, tmp, events, language, name) }
            if (output.exists()) output.delete()
            if (!tmp.renameTo(output)) {
                tmp.copyTo(output, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            output.delete()
            throw e
        }
    }

    private fun build(src: RandomAccessFile, out: File, events: List<SubtitleEvent>, language: String, name: String?) {
        val length = src.length()
        val boxes = topLevel(src, length)
        if (boxes.any { it.type == "moof" }) throw UnsupportedMp4Exception("fragmented mp4")
        val moov = boxes.singleOrNull { it.type == "moov" } ?: throw UnsupportedMp4Exception("no single moov")
        if (moov.size > MAX_MOOV_BYTES) throw UnsupportedMp4Exception("moov too large")
        if (boxes.none { it.type == "mdat" }) throw UnsupportedMp4Exception("no mdat")

        val moovPayload = ByteArray((moov.size - moov.header).toInt()).also {
            src.seek(moov.start + moov.header)
            src.readFully(it)
        }
        val info = readMovie(moovPayload)
        val samples = buildSamples(events, if (info.durationMs > 0) info.durationMs else Long.MAX_VALUE)
        if (samples.texts.isEmpty()) throw UnsupportedMp4Exception("no subtitle lines inside the film's length")

        val use64 = length + samples.dataSize + (moov.size shl 1) > 0xFFFF_0000L
        val track = trackBox(info, samples, language, name ?: "SubtitleHandler", use64, chunkOffset = 0L)
        // The new moov: old children + the subtitle trak, next_track_ID bumped.
        val newMoovSize = 8L + moovPayload.size + track.size
        val delta = newMoovSize - moov.size
        val moovEnd = moov.start + moov.size

        // Everything after the moov moves by delta; chunk offsets pointing there must follow.
        val patched = moovPayload.copyOf()
        setNextTrackId(patched, info.nextTrackId + 1)
        if (delta != 0L) shiftChunkOffsets(patched, moovEnd, delta)

        val dataBoxHeader = if (samples.dataSize + 8 > 0xFFFF_FFFFL) 16 else 8
        val outputBeforeData = length + delta
        val chunkOffset = outputBeforeData + dataBoxHeader
        val finalTrack = trackBox(info, samples, language, name ?: "SubtitleHandler", use64, chunkOffset)
        check(finalTrack.size == track.size)

        out.outputStream().buffered(1 shl 20).use { sink ->
            val buf = ByteArray(1 shl 20)
            fun copy(from: Long, to: Long) {
                var pos = from
                src.seek(from)
                while (pos < to) {
                    val n = src.read(buf, 0, minOf(buf.size.toLong(), to - pos).toInt())
                    if (n < 0) throw UnsupportedMp4Exception("input ended early")
                    sink.write(buf, 0, n)
                    pos += n
                }
            }
            copy(0, moov.start)
            sink.write(u32(newMoovSize))
            sink.write("moov".toByteArray(Charsets.ISO_8859_1))
            sink.write(patched)
            sink.write(finalTrack)
            copy(moovEnd, length)
            // The subtitle samples, in a data box of their own.
            if (dataBoxHeader == 8) sink.write(u32(samples.dataSize + 8)) else sink.write(u32(1))
            sink.write("mdat".toByteArray(Charsets.ISO_8859_1))
            if (dataBoxHeader == 16) sink.write(u64(samples.dataSize + 16))
            samples.texts.forEach { sink.write(it) }
        }
    }

    // --- Reading --------------------------------------------------------------------------------

    private fun topLevel(src: RandomAccessFile, length: Long): List<Box> {
        val boxes = mutableListOf<Box>()
        var pos = 0L
        val head = ByteArray(16)
        while (pos + 8 <= length) {
            src.seek(pos)
            src.readFully(head, 0, 8)
            var size = be(head, 0, 4)
            val type = String(head, 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                src.readFully(head, 8, 8)
                size = be(head, 8, 8)
                header = 16
            } else if (size == 0L) {
                size = length - pos
            }
            if (size < header || pos + size > length) throw UnsupportedMp4Exception("broken box '$type'")
            boxes += Box(type, pos, size, header)
            pos += size
        }
        if (pos != length) throw UnsupportedMp4Exception("trailing bytes")
        return boxes
    }

    private class Movie(val timescale: Long, val durationMs: Long, val nextTrackId: Long, val mvhdAt: Int, val version: Int)

    private fun readMovie(moov: ByteArray): Movie {
        var mvhd: Movie? = null
        walk(moov, 0, moov.size) { type, payloadStart, payloadEnd, _ ->
            if (type == "mvex") throw UnsupportedMp4Exception("fragmented mp4")
            if (type == "mvhd" && mvhd == null) {
                val version = moov[payloadStart].toInt()
                val base = payloadStart + 4
                val timescale: Long
                val duration: Long
                val idAt: Int
                if (version == 1) {
                    timescale = be(moov, base + 16, 4)
                    duration = be(moov, base + 20, 8)
                    idAt = base + 28 + 4 + 2 + 2 + 8 + 36 + 24
                } else {
                    timescale = be(moov, base + 8, 4)
                    duration = be(moov, base + 12, 4)
                    idAt = base + 16 + 4 + 2 + 2 + 8 + 36 + 24
                }
                if (timescale <= 0 || idAt + 4 > payloadEnd) throw UnsupportedMp4Exception("bad mvhd")
                mvhd = Movie(timescale, duration * 1000 / timescale, be(moov, idAt, 4), idAt, version)
            }
            false
        }
        val movie = mvhd ?: throw UnsupportedMp4Exception("no mvhd")
        // next_track_ID may be stale; use one past the highest track id actually present.
        var highest = movie.nextTrackId - 1
        walk(moov, 0, moov.size) { type, payloadStart, _, depthParent ->
            if (type == "tkhd" && depthParent == "trak") {
                val version = moov[payloadStart].toInt()
                val id = be(moov, payloadStart + 4 + if (version == 1) 16 else 8, 4)
                if (id > highest) highest = id
            }
            false
        }
        return Movie(movie.timescale, movie.durationMs, highest + 1, movie.mvhdAt, movie.version)
    }

    private fun setNextTrackId(moov: ByteArray, value: Long) {
        val movie = readMovie(moov)
        putBe(moov, movie.mvhdAt, value, 4)
    }

    /** Visits boxes depth-first inside the known containers; [visit] returns true to stop. */
    private fun walk(buf: ByteArray, from: Int, to: Int, parent: String = "", visit: (String, Int, Int, String) -> Boolean): Boolean {
        var pos = from
        while (pos + 8 <= to) {
            var size = be(buf, pos, 4)
            val type = String(buf, pos + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = be(buf, pos + 8, 8); header = 16 } else if (size == 0L) size = (to - pos).toLong()
            if (size < header || pos + size > to) throw UnsupportedMp4Exception("broken box '$type' inside moov")
            val payloadStart = pos + header
            val payloadEnd = (pos + size).toInt()
            if (visit(type, payloadStart, payloadEnd, parent)) return true
            if (type in CONTAINERS && walk(buf, payloadStart, payloadEnd, type, visit)) return true
            pos = payloadEnd
        }
        return false
    }

    /** Adds [delta] to every chunk offset that points at or after [threshold]. */
    private fun shiftChunkOffsets(moov: ByteArray, threshold: Long, delta: Long) {
        walk(moov, 0, moov.size) { type, payloadStart, payloadEnd, _ ->
            if (type == "stco" || type == "co64") {
                val wide = type == "co64"
                val count = be(moov, payloadStart + 4, 4).toInt()
                val step = if (wide) 8 else 4
                if (payloadStart + 8 + count.toLong() * step > payloadEnd) throw UnsupportedMp4Exception("broken $type")
                for (i in 0 until count) {
                    val at = payloadStart + 8 + i * step
                    val value = be(moov, at, step)
                    if (value >= threshold) {
                        val shifted = value + delta
                        if (!wide && shifted > 0xFFFF_FFFFL) throw UnsupportedMp4Exception("offset beyond 4 GB")
                        putBe(moov, at, shifted, step)
                    }
                }
            }
            false
        }
    }

    // --- Subtitle samples -----------------------------------------------------------------------

    private class Samples(val texts: List<ByteArray>, val durations: List<Long>, val totalMs: Long) {
        val dataSize: Long get() = texts.sumOf { it.size.toLong() }
    }

    private val tags = Regex("<[^>]*>|\\{\\\\[^}]*\\}")

    /** Cue text, then gaps as empty samples, as ffmpeg does; lines past the end of the film are dropped. */
    private fun buildSamples(events: List<SubtitleEvent>, filmMs: Long): Samples {
        val cues = events
            .map { SubtitleEvent(it.startMs, it.endMs, it.text.replace(tags, "").replace("\r", "").trim()) }
            .filter { it.text.isNotEmpty() && it.endMs > it.startMs && it.startMs in 0 until filmMs }
            .sortedBy { it.startMs }
        val texts = mutableListOf<ByteArray>()
        val durations = mutableListOf<Long>()
        var clock = 0L
        fun add(text: String, duration: Long) {
            val bytes = text.toByteArray(Charsets.UTF_8).let { if (it.size > 0xFFFF) it.copyOf(0xFFFF) else it }
            texts += ByteArray(2 + bytes.size).also {
                it[0] = (bytes.size shr 8).toByte()
                it[1] = bytes.size.toByte()
                bytes.copyInto(it, 2)
            }
            durations += duration
            clock += duration
        }
        cues.forEachIndexed { i, cue ->
            val next = cues.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
            val end = minOf(cue.endMs, next, filmMs)
            if (end <= clock || end <= cue.startMs) return@forEachIndexed
            val start = maxOf(cue.startMs, clock)
            if (start > clock) add("", start - clock)
            add(cue.text, end - start)
        }
        return Samples(texts, durations, clock)
    }

    // --- Writing the track ------------------------------------------------------------------------

    private fun trackBox(info: Movie, s: Samples, language: String, handlerName: String, wide: Boolean, chunkOffset: Long): ByteArray {
        val trackId = info.nextTrackId
        val movieTicks = s.totalMs * info.timescale / 1000

        val tkhd = fullBox("tkhd", 0, 3) {
            w32(0); w32(0)          // creation, modification
            w32(trackId)
            w32(0)                  // reserved
            w32(movieTicks)
            w32(0); w32(0)          // reserved
            w16(0); w16(0)          // layer, alternate group
            w16(0); w16(0)          // volume, reserved
            listOf(0x10000L, 0, 0, 0, 0x10000L, 0, 0, 0, 0x40000000L).forEach { w32(it) }
            w32(0); w32(0)          // width, height
        }
        val mdhd = fullBox("mdhd", 0, 0) {
            w32(0); w32(0)
            w32(SUBTITLE_TIMESCALE)
            w32(s.totalMs)
            w16(packLanguage(language))
            w16(0)
        }
        val hdlr = fullBox("hdlr", 0, 0) {
            w32(0)
            wraw("sbtl".toByteArray(Charsets.ISO_8859_1))
            w32(0); w32(0); w32(0)
            wraw(handlerName.toByteArray(Charsets.UTF_8)); w8(0)
        }
        val nmhd = fullBox("nmhd", 0, 0) { }
        val dinf = box("dinf", fullBoxWith("dref", 0, 0, u32(1) + fullBox("url ", 0, 1) { }))
        val stsd = fullBoxWith("stsd", 0, 0, u32(1) + tx3gEntry())
        val stts = fullBoxWith("stts", 0, 0, run {
            val runs = mutableListOf<Pair<Long, Long>>()
            s.durations.forEach { d -> if (runs.isNotEmpty() && runs.last().second == d) runs[runs.lastIndex] = runs.last().first + 1 to d else runs += 1L to d }
            u32(runs.size.toLong()) + runs.map { u32(it.first) + u32(it.second) }.fold(ByteArray(0)) { a, b -> a + b }
        })
        val stsc = fullBoxWith("stsc", 0, 0, u32(1) + u32(1) + u32(s.texts.size.toLong()) + u32(1))
        val stsz = fullBoxWith(
            "stsz", 0, 0,
            u32(0) + u32(s.texts.size.toLong()) + s.texts.map { u32(it.size.toLong()) }.fold(ByteArray(0)) { a, b -> a + b },
        )
        val offsets = if (wide) fullBoxWith("co64", 0, 0, u32(1) + u64(chunkOffset)) else fullBoxWith("stco", 0, 0, u32(1) + u32(chunkOffset))
        val stbl = box("stbl", stsd + stts + stsc + stsz + offsets)
        val minf = box("minf", nmhd + dinf + stbl)
        val mdia = box("mdia", mdhd + hdlr + minf)
        return box("trak", tkhd + mdia)
    }

    private fun tx3gEntry(): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write(ByteArray(6)); write(u16b(1))        // reserved, data reference index
            write(u32(0))                               // display flags
            write(byteArrayOf(1, -1))                   // horizontal justification left, vertical bottom
            write(u32(0))                               // background colour (transparent)
            write(ByteArray(8))                         // default text box
            write(u16b(0)); write(u16b(0)); write(u16b(1)) // style: start, end, font id
            write(byteArrayOf(0, 18)); write(u32(0xFFFFFFFFL)) // face style, font size, white
            write(box("ftab", u16b(1) + u16b(1) + byteArrayOf(5) + "Serif".toByteArray(Charsets.ISO_8859_1)))
        }.toByteArray()
        return box("tx3g", body)
    }

    private fun packLanguage(code: String): Int {
        val c = code.lowercase().padEnd(3, 'd').take(3)
        if (c.any { it !in 'a'..'z' }) return packLanguage("und")
        return ((c[0].code - 0x60) shl 10) or ((c[1].code - 0x60) shl 5) or (c[2].code - 0x60)
    }

    // --- Byte helpers ----------------------------------------------------------------------------

    private class Writer {
        val out = ByteArrayOutputStream()
        fun w8(v: Int) = out.write(v)
        fun w16(v: Int) { out.write(v shr 8); out.write(v) }
        fun w32(v: Long) { out.write(u32(v)) }
        fun wraw(b: ByteArray) = out.write(b)
    }

    private fun fullBox(type: String, version: Int, flags: Int, body: Writer.() -> Unit): ByteArray =
        fullBoxWith(type, version, flags, Writer().apply(body).out.toByteArray())

    private fun fullBoxWith(type: String, version: Int, flags: Int, body: ByteArray): ByteArray =
        box(type, byteArrayOf(version.toByte(), (flags shr 16).toByte(), (flags shr 8).toByte(), flags.toByte()) + body)

    private fun box(type: String, body: ByteArray): ByteArray =
        u32(8L + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body

    private fun u16b(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun u64(v: Long) = u32(v ushr 32) + u32(v and 0xFFFF_FFFFL)

    private fun be(b: ByteArray, at: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    private fun putBe(b: ByteArray, at: Int, value: Long, n: Int) {
        for (i in 0 until n) b[at + i] = (value shr (8 * (n - 1 - i))).toByte()
    }
}
