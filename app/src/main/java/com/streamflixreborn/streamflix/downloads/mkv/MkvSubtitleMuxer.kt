package com.streamflixreborn.streamflix.downloads.mkv

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** The file is not an MKV this muxer can rewrite (the caller keeps the subtitle as a separate file). */
class UnsupportedMkvException(message: String) : Exception(message)

/** One subtitle line: shown from [startMs] to [endMs]. */
data class SubtitleEvent(val startMs: Long, val endMs: Long, val text: String)

/** Reads SubRip (.srt) text into [SubtitleEvent]s. */
object SrtParser {
    private val timing = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})")

    /** UTF-8 when the bytes are valid UTF-8, otherwise [fallback] (Central European files are usually windows-1250). */
    fun decode(bytes: ByteArray, fallback: Charset = Charsets.ISO_8859_1): String {
        var start = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) start = 3
        val body = bytes.copyOfRange(start, bytes.size)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString()
        } catch (_: Exception) {
            String(body, fallback)
        }
    }

    fun parse(text: String): List<SubtitleEvent> {
        val events = mutableListOf<SubtitleEvent>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            val match = timing.find(lines[i])
            if (match == null) { i++; continue }
            val g = match.groupValues
            fun ms(h: String, m: String, s: String, f: String): Long =
                h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + f.padEnd(3, '0').toLong()
            val start = ms(g[1], g[2], g[3], g[4])
            val end = ms(g[5], g[6], g[7], g[8])
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i].trim())
                i++
            }
            if (body.isNotEmpty() && end >= start) events += SubtitleEvent(start, end, body.toString())
        }
        return events.sortedBy { it.startMs }
    }
}

/**
 * Adds one SRT subtitle track to a Matroska file without re-encoding: the video and audio are copied
 * as they are, the subtitle lines are inserted as blocks of a new track, and the index (Cues) and
 * SeekHead are rewritten for the new positions. Other players then list it like any embedded track.
 *
 * Handles the usual files (known-size Segment and Clusters, as mkvmerge and ffmpeg write them).
 * Anything else throws [UnsupportedMkvException] and the input is left untouched.
 */
object MkvSubtitleMuxer {
    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEKHEAD = 0x114D9B74L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_CUES = 0x1C53BB6BL
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_TAGS = 0x1254C367L
    private const val ID_CHAPTERS = 0x1043A770L
    private const val ID_VOID = 0xECL
    private const val ID_CRC32 = 0xBFL
    private const val ID_TIMESTAMP = 0xE7L
    private const val ID_SIMPLEBLOCK = 0xA3L
    private const val ID_BLOCKGROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_BLOCKDURATION = 0x9BL
    private const val ID_TIMESTAMP_SCALE = 0x2AD7B1L
    private const val ID_TRACKENTRY = 0xAEL
    private const val ID_TRACKNUMBER = 0xD7L
    private const val ID_TRACKUID = 0x73C5L
    private const val ID_TRACKTYPE = 0x83L
    private const val ID_CODECID = 0x86L
    private const val ID_LANGUAGE = 0x22B59CL
    private const val ID_NAME = 0x536EL
    private const val ID_FLAGDEFAULT = 0x88L
    private const val ID_FLAGLACING = 0x9CL
    private const val ID_CUEPOINT = 0xBBL
    private const val ID_CUETIME = 0xB3L
    private const val ID_CUETRACKPOSITIONS = 0xB7L
    private const val ID_CUETRACK = 0xF7L
    private const val ID_CUECLUSTERPOSITION = 0xF1L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEKID = 0x53ABL
    private const val ID_SEEKPOSITION = 0x53ACL

    private const val SEEKHEAD_RESERVE = 128
    private const val MAX_CLUSTER_BYTES = 96L shl 20
    private const val MAX_SMALL_ELEMENT_BYTES = 64L shl 20
    private const val MIN_REL = -32768L
    private const val MAX_REL = 32767L
    private const val SYNTHETIC_SPAN_TICKS = 20_000L

    /** [events] must be sorted by start. Writes [output]; deletes it again if anything fails. */
    fun mux(
        input: File,
        output: File,
        events: List<SubtitleEvent>,
        language: String = "eng",
        name: String? = null,
        makeDefault: Boolean = true,
    ) {
        val sorted = events.sortedBy { it.startMs }
        var ok = false
        try {
            Source(input).use { src -> Sink(output).use { sink -> Job(src, sink, sorted, language, name, makeDefault).run() } }
            ok = true
        } finally {
            if (!ok) output.delete()
        }
    }

    // ---- element helpers -------------------------------------------------------------------

    private class Header(val id: Long, val size: Long, val start: Long, val headerLen: Int, val raw: ByteArray) {
        val dataStart: Long get() = start + headerLen
        val end: Long get() = dataStart + size
    }

    private fun idBytes(id: Long): ByteArray {
        val len = when {
            id > 0xFFFFFFL -> 4
            id > 0xFFFFL -> 3
            id > 0xFFL -> 2
            else -> 1
        }
        return ByteArray(len) { i -> (id shr (8 * (len - 1 - i))).toByte() }
    }

    private fun sizeBytes(size: Long): ByteArray {
        var len = 1
        while (len < 8 && size >= (1L shl (7 * len)) - 1) len++
        val marker = 1L shl (7 * len)
        val v = marker or size
        return ByteArray(len) { i -> (v shr (8 * (len - 1 - i))).toByte() }
    }

    private fun element(id: Long, payload: ByteArray): ByteArray {
        val h = idBytes(id)
        val s = sizeBytes(payload.size.toLong())
        return ByteArray(h.size + s.size + payload.size).also {
            System.arraycopy(h, 0, it, 0, h.size)
            System.arraycopy(s, 0, it, h.size, s.size)
            System.arraycopy(payload, 0, it, h.size + s.size, payload.size)
        }
    }

    private fun uintPayload(value: Long): ByteArray {
        var len = 1
        while (len < 8 && (value ushr (8 * len)) != 0L) len++
        return ByteArray(len) { i -> (value shr (8 * (len - 1 - i))).toByte() }
    }

    private fun uint(id: Long, value: Long) = element(id, uintPayload(value))
    private fun fixedUint8(id: Long, value: Long) = element(id, ByteArray(8) { i -> (value shr (8 * (7 - i))).toByte() })
    private fun string(id: Long, value: String) = element(id, value.toByteArray(Charsets.UTF_8))

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** Reads an element header from a byte array; null at the end or on nonsense. */
    private fun headerAt(buf: ByteArray, pos: Int, base: Long): Header? {
        if (pos >= buf.size) return null
        val first = buf[pos].toInt() and 0xFF
        if (first == 0) return null
        val idLen = Integer.numberOfLeadingZeros(first) - 24 + 1
        if (idLen > 4 || pos + idLen >= buf.size) return null
        var id = 0L
        for (i in 0 until idLen) id = (id shl 8) or (buf[pos + i].toLong() and 0xFF)
        val sFirst = buf[pos + idLen].toInt() and 0xFF
        if (sFirst == 0) return null
        val sLen = Integer.numberOfLeadingZeros(sFirst) - 24 + 1
        if (pos + idLen + sLen > buf.size) return null
        var size = (sFirst and ((1 shl (8 - sLen)) - 1)).toLong()
        var allOnes = size == ((1L shl (8 - sLen)) - 1)
        for (i in 1 until sLen) {
            val b = buf[pos + idLen + i].toLong() and 0xFF
            if (b != 0xFFL) allOnes = false
            size = (size shl 8) or b
        }
        if (allOnes) throw UnsupportedMkvException("unknown-size element inside a cluster")
        val headerLen = idLen + sLen
        return Header(id, size, base + pos, headerLen, buf.copyOfRange(pos, pos + headerLen))
    }

    private fun readUInt(buf: ByteArray, from: Int, len: Int): Long {
        var v = 0L
        for (i in 0 until len) v = (v shl 8) or (buf[from + i].toLong() and 0xFF)
        return v
    }

    // ---- file access -------------------------------------------------------------------------

    private class Source(file: File) : Closeable {
        private val raf = RandomAccessFile(file, "r")
        val length: Long = raf.length()
        private val buf = ByteArray(1 shl 17)
        private var bufStart = 0L
        private var bufLen = 0

        fun readAt(pos: Long, dst: ByteArray, off: Int, len: Int) {
            var done = 0
            while (done < len) {
                val p = pos + done
                if (p < bufStart || p >= bufStart + bufLen) {
                    raf.seek(p)
                    bufLen = raf.read(buf, 0, buf.size)
                    bufStart = p
                    if (bufLen <= 0) { bufLen = 0; throw UnsupportedMkvException("the file ends early") }
                }
                val n = minOf(len - done, (bufStart + bufLen - p).toInt())
                System.arraycopy(buf, (p - bufStart).toInt(), dst, off + done, n)
                done += n
            }
        }

        fun readBytes(pos: Long, len: Long): ByteArray {
            if (len > Int.MAX_VALUE) throw UnsupportedMkvException("element too large")
            return ByteArray(len.toInt()).also { readAt(pos, it, 0, it.size) }
        }

        /** The element header at [pos] (IDs up to 4 bytes, sizes up to 8). */
        fun header(pos: Long): Header? {
            if (pos >= length) return null
            val avail = minOf(12L, length - pos).toInt()
            val tmp = ByteArray(avail)
            readAt(pos, tmp, 0, avail)
            val first = tmp[0].toInt() and 0xFF
            if (first == 0) throw UnsupportedMkvException("bad element id at $pos")
            val idLen = Integer.numberOfLeadingZeros(first) - 24 + 1
            if (idLen > 4 || idLen >= avail) throw UnsupportedMkvException("bad element at $pos")
            var id = 0L
            for (i in 0 until idLen) id = (id shl 8) or (tmp[i].toLong() and 0xFF)
            val sFirst = tmp[idLen].toInt() and 0xFF
            if (sFirst == 0) throw UnsupportedMkvException("bad element size at $pos")
            val sLen = Integer.numberOfLeadingZeros(sFirst) - 24 + 1
            if (idLen + sLen > avail) throw UnsupportedMkvException("bad element size at $pos")
            var size = (sFirst and ((1 shl (8 - sLen)) - 1)).toLong()
            var allOnes = size == ((1L shl (8 - sLen)) - 1)
            for (i in 1 until sLen) {
                val b = tmp[idLen + i].toLong() and 0xFF
                if (b != 0xFFL) allOnes = false
                size = (size shl 8) or b
            }
            if (allOnes) throw UnsupportedMkvException("unknown-size element $id")
            val headerLen = idLen + sLen
            return Header(id, size, pos, headerLen, tmp.copyOfRange(0, headerLen))
        }

        override fun close() = raf.close()
    }

    private class Sink(file: File) : Closeable {
        private val fos = FileOutputStream(file)
        private val out = BufferedOutputStream(fos, 1 shl 20)
        var pos = 0L
            private set

        fun write(bytes: ByteArray) {
            out.write(bytes)
            pos += bytes.size
        }

        /** Overwrites bytes already written (SeekHead and Segment size are only known at the end). */
        fun patch(position: Long, bytes: ByteArray) {
            out.flush()
            fos.channel.write(ByteBuffer.wrap(bytes), position)
        }

        override fun close() {
            out.flush()
            out.close()
        }
    }

    // ---- the job ---------------------------------------------------------------------------------

    private class CuePoint(val timeTicks: Long, val track: Long, val oldClusterPos: Long)

    private class Job(
        val src: Source,
        val sink: Sink,
        val events: List<SubtitleEvent>,
        val language: String,
        val name: String?,
        val makeDefault: Boolean,
    ) {
        var scale = 1_000_000L
        var sawInfo = false
        var subtitleTrack = 0L
        var firstVideoTrack = 1L
        var tracksSeen = false
        var next = 0
        var segDataStartIn = 0L
        var segDataStartOut = 0L
        val newPosOfOld = HashMap<Long, Long>()
        val generatedCues = mutableListOf<Pair<Long, Long>>() // cluster timestamp -> new position
        val sourceCues = mutableListOf<CuePoint>()
        val seekEntries = LinkedHashMap<Long, Long>() // element id -> position relative to segment data
        var clustersStarted = false

        fun ticks(ms: Long): Long = ms * 1_000_000L / scale

        fun run() {
            val ebml = src.header(0) ?: throw UnsupportedMkvException("empty file")
            if (ebml.id != ID_EBML) throw UnsupportedMkvException("not a Matroska file")
            sink.write(src.readBytes(0, ebml.headerLen + ebml.size))
            val segment = src.header(ebml.end) ?: throw UnsupportedMkvException("no Segment")
            if (segment.id != ID_SEGMENT) throw UnsupportedMkvException("no Segment")
            segDataStartIn = segment.dataStart
            val segmentEnd = segment.end
            if (segmentEnd > src.length) throw UnsupportedMkvException("the file is incomplete")

            sink.write(idBytes(ID_SEGMENT))
            val sizePos = sink.pos
            sink.write(ByteArray(8)) // patched at the end
            segDataStartOut = sink.pos
            val seekHeadPos = sink.pos
            sink.write(element(ID_VOID, ByteArray(SEEKHEAD_RESERVE - 2))) // 1 id byte + 1 size byte

            var pos = segDataStartIn
            while (pos < segmentEnd) {
                val h = src.header(pos) ?: break
                when (h.id) {
                    ID_SEEKHEAD, ID_VOID, ID_CRC32 -> Unit
                    ID_INFO -> {
                        val payload = readSmall(h)
                        scale = findUInt(payload, ID_TIMESTAMP_SCALE) ?: 1_000_000L
                        sawInfo = true
                        seekEntries[ID_INFO] = sink.pos - segDataStartOut
                        sink.write(h.raw); sink.write(payload)
                    }
                    ID_TRACKS -> {
                        val payload = readSmall(h)
                        seekEntries[ID_TRACKS] = sink.pos - segDataStartOut
                        sink.write(newTracks(payload))
                        tracksSeen = true
                    }
                    ID_CLUSTER -> {
                        if (!sawInfo || !tracksSeen) throw UnsupportedMkvException("clusters before Info/Tracks")
                        clustersStarted = true
                        cluster(h, segmentEnd)
                    }
                    ID_CUES -> readCues(readSmall(h))
                    ID_TAGS, ID_CHAPTERS -> {
                        val payload = readSmall(h)
                        if (!clustersStarted) seekEntries[h.id] = sink.pos - segDataStartOut
                        sink.write(h.raw); sink.write(payload)
                    }
                    else -> {
                        val payload = readSmall(h)
                        sink.write(h.raw); sink.write(payload)
                    }
                }
                pos = h.end
            }
            if (!tracksSeen) throw UnsupportedMkvException("no Tracks")
            writeSynthetic(events.subList(next, events.size))
            next = events.size

            val cuesPos = sink.pos - segDataStartOut
            sink.write(buildCues())
            seekEntries[ID_CUES] = cuesPos

            sink.patch(sizePos, ByteArray(8).also { b ->
                val size = sink.pos - segDataStartOut
                b[0] = 0x01
                for (i in 1..7) b[i] = (size shr (8 * (7 - i))).toByte()
            })
            sink.patch(seekHeadPos, buildSeekHead())
        }

        private fun readSmall(h: Header): ByteArray {
            if (h.size > MAX_SMALL_ELEMENT_BYTES) throw UnsupportedMkvException("element too large")
            return src.readBytes(h.dataStart, h.size)
        }

        /** The first unsigned-integer child [id] of a master element's payload. */
        private fun findUInt(payload: ByteArray, id: Long): Long? {
            var p = 0
            while (p < payload.size) {
                val h = headerAt(payload, p, 0) ?: return null
                if (h.id == id) return readUInt(payload, p + h.headerLen, h.size.toInt())
                p += h.headerLen + h.size.toInt()
            }
            return null
        }

        private fun newTracks(payload: ByteArray): ByteArray {
            var p = 0
            var maxNumber = 0L
            var maxUid = 0L
            var videoFound = false
            while (p < payload.size) {
                val h = headerAt(payload, p, 0) ?: break
                if (h.id == ID_TRACKENTRY) {
                    val entry = payload.copyOfRange(p + h.headerLen, p + h.headerLen + h.size.toInt())
                    val number = findUInt(entry, ID_TRACKNUMBER) ?: 0L
                    maxNumber = maxOf(maxNumber, number)
                    maxUid = maxOf(maxUid, findUInt(entry, ID_TRACKUID) ?: 0L)
                    if (!videoFound && findUInt(entry, ID_TRACKTYPE) == 1L) { firstVideoTrack = number; videoFound = true }
                }
                p += h.headerLen + h.size.toInt()
            }
            subtitleTrack = maxNumber + 1
            val entry = element(
                ID_TRACKENTRY,
                concat(
                    uint(ID_TRACKNUMBER, subtitleTrack),
                    uint(ID_TRACKUID, maxOf(maxUid + 1, subtitleTrack * 7919L + 1)),
                    uint(ID_TRACKTYPE, 17),
                    uint(ID_FLAGDEFAULT, if (makeDefault) 1 else 0),
                    uint(ID_FLAGLACING, 0),
                    string(ID_CODECID, "S_TEXT/UTF8"),
                    string(ID_LANGUAGE, language),
                    if (name != null) string(ID_NAME, name) else ByteArray(0),
                ),
            )
            return element(ID_TRACKS, concat(payload, entry))
        }

        private fun readCues(payload: ByteArray) {
            var p = 0
            while (p < payload.size) {
                val point = headerAt(payload, p, 0) ?: break
                if (point.id == ID_CUEPOINT) {
                    val body = payload.copyOfRange(p + point.headerLen, p + point.headerLen + point.size.toInt())
                    val time = findUInt(body, ID_CUETIME) ?: 0L
                    var q = 0
                    while (q < body.size) {
                        val tp = headerAt(body, q, 0) ?: break
                        if (tp.id == ID_CUETRACKPOSITIONS) {
                            val inner = body.copyOfRange(q + tp.headerLen, q + tp.headerLen + tp.size.toInt())
                            val track = findUInt(inner, ID_CUETRACK)
                            val clusterPos = findUInt(inner, ID_CUECLUSTERPOSITION)
                            if (track != null && clusterPos != null) sourceCues += CuePoint(time, track, clusterPos)
                        }
                        q += tp.headerLen + tp.size.toInt()
                    }
                }
                p += point.headerLen + point.size.toInt()
            }
        }

        /** A cluster's own timestamp: its Timestamp child, found without reading the blocks. */
        private fun clusterTimestamp(h: Header): Long {
            var pos = h.dataStart
            while (pos < h.end) {
                val c = src.header(pos) ?: break
                if (c.id == ID_TIMESTAMP) return readUInt(src.readBytes(c.dataStart, c.size), 0, c.size.toInt())
                pos = c.end
            }
            throw UnsupportedMkvException("a cluster without a timestamp")
        }

        private fun subtitleBlock(event: SubtitleEvent, relTicks: Long): ByteArray {
            val trackVint = sizeBytes(subtitleTrack)
            val text = event.text.toByteArray(Charsets.UTF_8)
            val block = ByteArray(trackVint.size + 3 + text.size)
            System.arraycopy(trackVint, 0, block, 0, trackVint.size)
            block[trackVint.size] = (relTicks shr 8).toByte()
            block[trackVint.size + 1] = relTicks.toByte()
            block[trackVint.size + 2] = 0
            System.arraycopy(text, 0, block, trackVint.size + 3, text.size)
            val duration = maxOf(1L, ticks(event.endMs) - ticks(event.startMs))
            return element(ID_BLOCKGROUP, concat(element(ID_BLOCK, block), uint(ID_BLOCKDURATION, duration)))
        }

        private fun cluster(h: Header, segmentEnd: Long) {
            if (h.size > MAX_CLUSTER_BYTES) throw UnsupportedMkvException("cluster too large")
            val t0 = clusterTimestamp(h)
            // The next cluster decides which lines belong here.
            val following = if (h.end < segmentEnd) src.header(h.end) else null
            val tNext = if (following != null && following.id == ID_CLUSTER) clusterTimestamp(following) else Long.MAX_VALUE

            val embedded = mutableListOf<Pair<Long, ByteArray>>() // relative ticks -> block group
            val late = mutableListOf<SubtitleEvent>()
            val early = mutableListOf<SubtitleEvent>()
            while (next < events.size) {
                val e = events[next]
                val start = ticks(e.startMs)
                if (start >= tNext) break
                next++
                val rel = start - t0
                when {
                    rel > MAX_REL -> late += e
                    rel < MIN_REL -> early += e
                    else -> embedded += rel to subtitleBlock(e, rel)
                }
            }
            writeSynthetic(early)

            val payload = src.readBytes(h.dataStart, h.size)
            val kept = ByteArrayOutputStream()
            var p = 0
            val sub = embedded.iterator()
            var pending: Pair<Long, ByteArray>? = if (sub.hasNext()) sub.next() else null
            while (p < payload.size) {
                val c = headerAt(payload, p, 0) ?: throw UnsupportedMkvException("damaged cluster")
                val total = c.headerLen + c.size.toInt()
                if (c.id == ID_CRC32) { p += total; continue }
                if (c.id == ID_SIMPLEBLOCK || c.id == ID_BLOCKGROUP) {
                    val time = blockTime(payload, p + c.headerLen, c.id == ID_BLOCKGROUP, c.size.toInt())
                    while (pending != null && time != null && pending.first <= time) {
                        kept.write(pending.second)
                        pending = if (sub.hasNext()) sub.next() else null
                    }
                }
                kept.write(payload, p, total)
                p += total
            }
            while (pending != null) {
                kept.write(pending.second)
                pending = if (sub.hasNext()) sub.next() else null
            }

            val newPos = sink.pos - segDataStartOut
            newPosOfOld[h.start - segDataStartIn] = newPos
            generatedCues += t0 to newPos
            sink.write(idBytes(ID_CLUSTER))
            sink.write(sizeBytes(kept.size().toLong()))
            sink.write(kept.toByteArray())
            writeSynthetic(late)
        }

        /** Relative timestamp (ticks) of a SimpleBlock, or of the Block inside a BlockGroup. */
        private fun blockTime(buf: ByteArray, from: Int, group: Boolean, len: Int): Long? {
            if (!group) return trackAndTime(buf, from, len)
            var p = from
            val end = from + len
            while (p < end) {
                val h = headerAt(buf, p, 0) ?: return null
                if (h.id == ID_BLOCK) return trackAndTime(buf, p + h.headerLen, h.size.toInt())
                p += h.headerLen + h.size.toInt()
            }
            return null
        }

        private fun trackAndTime(buf: ByteArray, from: Int, len: Int): Long? {
            if (len < 4) return null
            val first = buf[from].toInt() and 0xFF
            if (first == 0) return null
            val trackLen = Integer.numberOfLeadingZeros(first) - 24 + 1
            if (trackLen + 2 > len) return null
            return ((buf[from + trackLen].toInt() shl 8) or (buf[from + trackLen + 1].toInt() and 0xFF)).toShort().toLong()
        }

        /** Lines that do not fit a cluster's 16-bit offsets get clusters of their own. */
        private fun writeSynthetic(lines: List<SubtitleEvent>) {
            var i = 0
            while (i < lines.size) {
                val t0 = ticks(lines[i].startMs)
                val blocks = ByteArrayOutputStream()
                blocks.write(uint(ID_TIMESTAMP, t0))
                while (i < lines.size && ticks(lines[i].startMs) - t0 <= SYNTHETIC_SPAN_TICKS) {
                    blocks.write(subtitleBlock(lines[i], ticks(lines[i].startMs) - t0))
                    i++
                }
                sink.write(element(ID_CLUSTER, blocks.toByteArray()))
            }
        }

        private fun buildCues(): ByteArray {
            val points = ByteArrayOutputStream()
            fun point(time: Long, track: Long, pos: Long) {
                points.write(
                    element(
                        ID_CUEPOINT,
                        concat(
                            uint(ID_CUETIME, time),
                            element(ID_CUETRACKPOSITIONS, concat(uint(ID_CUETRACK, track), uint(ID_CUECLUSTERPOSITION, pos))),
                        ),
                    ),
                )
            }
            val mapped = sourceCues.mapNotNull { c -> newPosOfOld[c.oldClusterPos]?.let { Triple(c.timeTicks, c.track, it) } }
            if (mapped.isNotEmpty()) {
                mapped.sortedBy { it.first }.forEach { point(it.first, it.second, it.third) }
            } else {
                generatedCues.forEach { point(it.first, firstVideoTrack, it.second) }
            }
            return element(ID_CUES, points.toByteArray())
        }

        private fun buildSeekHead(): ByteArray {
            val entries = ByteArrayOutputStream()
            seekEntries.entries.take(5).forEach { (id, position) ->
                entries.write(element(ID_SEEK, concat(element(ID_SEEKID, idBytes(id)), fixedUint8(ID_SEEKPOSITION, position))))
            }
            val seekHead = element(ID_SEEKHEAD, entries.toByteArray())
            val spare = SEEKHEAD_RESERVE - seekHead.size
            check(spare == 0 || spare >= 2) { "SeekHead does not fit" }
            val filler = when {
                spare == 0 -> ByteArray(0)
                else -> element(ID_VOID, ByteArray(spare - 2)) // 1 id + 1 size byte
            }
            return concat(seekHead, filler)
        }
    }
}
