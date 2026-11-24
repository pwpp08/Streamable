package com.streamflixreborn.streamflix.downloads.mkv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MkvSubtitleMuxerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val events = listOf(
        SubtitleEvent(0, 1_500, "First"),
        SubtitleEvent(2_000, 3_500, "Second\nline two"),
        SubtitleEvent(10_250, 12_000, "Ten and a bit"),
        SubtitleEvent(29_000, 30_000, "Near the end"),
        SubtitleEvent(100_000, 101_000, "After the film ends"),
    )

    private fun fixture(name: String): File {
        val file = folder.newFile(name)
        javaClass.getResourceAsStream("/mkv/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    // ---- a small reader, independent of the muxer, to check the result ------------------------------

    private class El(val id: Long, val start: Int, val dataStart: Int, val size: Int) {
        val end: Int get() = dataStart + size
    }

    private fun el(buf: ByteArray, pos: Int): El {
        val first = buf[pos].toInt() and 0xFF
        val idLen = Integer.numberOfLeadingZeros(first) - 24 + 1
        var id = 0L
        for (i in 0 until idLen) id = (id shl 8) or (buf[pos + i].toLong() and 0xFF)
        val sFirst = buf[pos + idLen].toInt() and 0xFF
        val sLen = Integer.numberOfLeadingZeros(sFirst) - 24 + 1
        var size = (sFirst and ((1 shl (8 - sLen)) - 1)).toLong()
        for (i in 1 until sLen) size = (size shl 8) or (buf[pos + idLen + i].toLong() and 0xFF)
        return El(id, pos, pos + idLen + sLen, size.toInt())
    }

    private fun children(buf: ByteArray, parent: El): List<El> {
        val out = mutableListOf<El>()
        var p = parent.dataStart
        while (p < parent.end) {
            val e = el(buf, p)
            out += e
            p = e.end
        }
        assertEquals("children must end exactly at the parent's end", parent.end, p)
        return out
    }

    private fun uint(buf: ByteArray, e: El): Long {
        var v = 0L
        for (i in 0 until e.size) v = (v shl 8) or (buf[e.dataStart + i].toLong() and 0xFF)
        return v
    }

    private class Block(val track: Long, val relTicks: Long, val data: ByteArray)

    private fun blockOf(buf: ByteArray, e: El): Block {
        val first = buf[e.dataStart].toInt() and 0xFF
        val tl = Integer.numberOfLeadingZeros(first) - 24 + 1
        var track = (first and ((1 shl (8 - tl)) - 1)).toLong()
        for (i in 1 until tl) track = (track shl 8) or (buf[e.dataStart + i].toLong() and 0xFF)
        val rel = ((buf[e.dataStart + tl].toInt() shl 8) or (buf[e.dataStart + tl + 1].toInt() and 0xFF)).toShort().toLong()
        return Block(track, rel, buf.copyOfRange(e.dataStart + tl + 3, e.end))
    }

    private fun clusterBlocks(buf: ByteArray, cluster: El): Pair<Long, List<Block>> {
        var t0 = 0L
        val blocks = mutableListOf<Block>()
        for (c in children(buf, cluster)) {
            when (c.id) {
                0xE7L -> t0 = uint(buf, c)
                0xA3L -> blocks += blockOf(buf, c)
                0xA0L -> children(buf, c).filter { it.id == 0xA1L }.forEach { blocks += blockOf(buf, it) }
            }
        }
        return t0 to blocks
    }

    private fun checkMux(name: String) {
        val input = fixture(name)
        val output = File(folder.root, "out_$name")
        MkvSubtitleMuxer.mux(input, output, events, language = "eng", name = "English")
        System.getenv("MKV_TEST_OUT")?.let { dir -> File(dir).mkdirs(); output.copyTo(File(dir, "out_$name"), overwrite = true) }

        val before = input.readBytes()
        val after = output.readBytes()

        // Top level: EBML header, then one Segment that fills the file.
        val top = children(after, El(0, 0, 0, after.size))
        assertEquals(listOf(0x1A45DFA3L, 0x18538067L), top.map { it.id })
        val segment = top[1]
        assertEquals(after.size, segment.end)
        val parts = children(after, segment)
        assertEquals(0x114D9B74L, parts.first().id)
        assertEquals(0xECL, parts[1].id)
        assertEquals(0x1C53BB6BL, parts.last().id)

        // The SeekHead points at the right elements.
        val seekEntries = children(after, parts.first()).map { seek ->
            val kids = children(after, seek)
            val id = kids.first { it.id == 0x53ABL }.let { e -> (0 until e.size).fold(0L) { a, i -> (a shl 8) or (after[e.dataStart + i].toLong() and 0xFF) } }
            id to uint(after, kids.first { it.id == 0x53ACL })
        }
        assertTrue(seekEntries.map { it.first }.containsAll(listOf(0x1549A966L, 0x1654AE6BL, 0x1C53BB6BL)))
        seekEntries.forEach { (id, pos) -> assertEquals(id, el(after, segment.dataStart + pos.toInt()).id) }

        // Tracks: the original two plus a subtitle track.
        val tracks = parts.first { it.id == 0x1654AE6BL }
        val entries = children(after, tracks).filter { it.id == 0xAEL }
        assertEquals(3, entries.size)
        val subtitleEntry = children(after, entries.last())
        assertEquals(17L, uint(after, subtitleEntry.first { it.id == 0x83L }))
        val codec = subtitleEntry.first { it.id == 0x86L }
        assertEquals("S_TEXT/UTF8", String(after, codec.dataStart, codec.size))
        val subtitleTrack = uint(after, subtitleEntry.first { it.id == 0xD7L })
        assertEquals(3L, subtitleTrack)

        // Cues lead to clusters.
        val clusters = parts.filter { it.id == 0x1F43B675L }
        val clusterStarts: Set<Long> = clusters.map { (it.start - segment.dataStart).toLong() }.toSet()
        val cuePoints = children(after, parts.last())
        assertTrue(cuePoints.isNotEmpty())
        cuePoints.forEach { point ->
            val positions = children(after, point).first { it.id == 0xB7L }
            val pos = uint(after, children(after, positions).first { it.id == 0xF1L })
            assertTrue("cue points at a cluster", pos in clusterStarts)
        }

        // Every subtitle line is there once, at the right time, and nothing else was lost.
        val found = mutableListOf<Pair<Long, String>>()
        var videoAudioAfter = 0
        clusters.forEach { cluster ->
            val (t0, blocks) = clusterBlocks(after, cluster)
            blocks.forEach { b ->
                if (b.track == subtitleTrack) found += (t0 + b.relTicks) to String(b.data, Charsets.UTF_8) else videoAudioAfter++
            }
        }
        val expected: List<Pair<Long, String>> = events.map { it.startMs to it.text }
        val actual: List<Pair<Long, String>> = found.sortedWith(compareBy<Pair<Long, String>> { it.first })
        assertEquals(expected, actual)

        var videoAudioBefore = 0
        val src = children(before, children(before, El(0, 0, 0, before.size))[1])
        src.filter { it.id == 0x1F43B675L }.forEach { videoAudioBefore += clusterBlocks(before, it).second.size }
        assertEquals("video and audio blocks are copied as they were", videoAudioBefore, videoAudioAfter)

        // Timestamps of the clusters never go backwards.
        val times = clusters.map { clusterBlocks(after, it).first }
        assertEquals(times.sorted(), times)
    }

    @Test
    fun `a file from ffmpeg gets a subtitle track`() = checkMux("ffmpeg_1s_clusters.mkv")

    @Test
    fun `a file from mkvmerge gets a subtitle track`() = checkMux("mkvmerge_2s.mkv")

    @Test
    fun `a failed mux leaves no output`() {
        val notMkv = folder.newFile("plain.mp4").apply { writeBytes(ByteArray(2048) { 7 }) }
        val output = File(folder.root, "never.mkv")
        try {
            MkvSubtitleMuxer.mux(notMkv, output, events)
            throw AssertionError("expected UnsupportedMkvException")
        } catch (_: UnsupportedMkvException) {
        }
        assertFalse(output.exists())
    }

    @Test
    fun `srt text is read with its timing and several lines`() {
        val srt = "﻿1\r\n00:00:01,000 --> 00:00:02,500\r\nHello\r\nthere\r\n\r\n2\r\n00:01:00,250 --> 00:01:01,000\r\n<i>Late</i>\r\n"
        val parsed = SrtParser.parse(SrtParser.decode(srt.toByteArray(Charsets.UTF_8)))
        assertEquals(listOf(SubtitleEvent(1_000, 2_500, "Hello\nthere"), SubtitleEvent(60_250, 61_000, "<i>Late</i>")), parsed)
    }

    @Test
    fun `an srt in a legacy encoding falls back to the given charset`() {
        val bytes = "1\n00:00:01,000 --> 00:00:02,000\nőű á\n".toByteArray(charset("windows-1250"))
        val text = SrtParser.decode(bytes, charset("windows-1250"))
        assertEquals("őű á", SrtParser.parse(text).single().text)
    }
}
