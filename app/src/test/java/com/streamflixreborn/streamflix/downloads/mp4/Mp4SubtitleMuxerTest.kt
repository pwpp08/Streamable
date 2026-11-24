package com.streamflixreborn.streamflix.downloads.mp4

import com.streamflixreborn.streamflix.downloads.mkv.SubtitleEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Mp4SubtitleMuxerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val events = listOf(
        SubtitleEvent(0, 1_000, "First <i>line</i>"),
        SubtitleEvent(1_500, 2_000, "Second\nline"),
        SubtitleEvent(2_400, 2_900, "{\\an8}Near the end"),
        SubtitleEvent(10_000, 11_000, "After the film ends"),
    )

    private fun fixture(name: String): File {
        val file = folder.newFile(name)
        javaClass.getResourceAsStream("/mp4/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    // ---- a tiny independent reader --------------------------------------------------------------

    private class B(val type: String, val start: Int, val dataStart: Int, val end: Int)

    private fun be(buf: ByteArray, at: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (buf[at + i].toLong() and 0xFF)
        return v
    }

    private fun boxes(buf: ByteArray, from: Int, to: Int): List<B> {
        val out = mutableListOf<B>()
        var p = from
        while (p < to) {
            var size = be(buf, p, 4)
            val type = String(buf, p + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = be(buf, p + 8, 8); header = 16 }
            out += B(type, p, p + header, (p + size).toInt())
            p += size.toInt()
        }
        assertEquals("boxes must end exactly where their parent ends", to, p)
        return out
    }

    private fun child(buf: ByteArray, parent: B, type: String) = boxes(buf, parent.dataStart, parent.end).first { it.type == type }

    private class Track(val id: Long, val handler: String, val offsets: List<Long>, val sizes: List<Long>, val samplesPerChunk: Long, val entry: String)

    private fun tracks(buf: ByteArray): List<Track> {
        val top = boxes(buf, 0, buf.size)
        val moov = top.first { it.type == "moov" }
        return boxes(buf, moov.dataStart, moov.end).filter { it.type == "trak" }.map { trak ->
            val tkhd = child(buf, trak, "tkhd")
            val id = be(buf, tkhd.dataStart + 4 + 8, 4)
            val mdia = child(buf, trak, "mdia")
            val hdlr = child(buf, mdia, "hdlr")
            val handler = String(buf, hdlr.dataStart + 8, 4, Charsets.ISO_8859_1)
            val stbl = child(buf, child(buf, mdia, "minf"), "stbl")
            val stsd = child(buf, stbl, "stsd")
            val entry = String(buf, stsd.dataStart + 8 + 4, 4, Charsets.ISO_8859_1)
            val offsetsBox = boxes(buf, stbl.dataStart, stbl.end).first { it.type == "stco" || it.type == "co64" }
            val wide = offsetsBox.type == "co64"
            val count = be(buf, offsetsBox.dataStart + 4, 4).toInt()
            val offsets = (0 until count).map { be(buf, offsetsBox.dataStart + 8 + it * (if (wide) 8 else 4), if (wide) 8 else 4) }
            val stsz = child(buf, stbl, "stsz")
            val n = be(buf, stsz.dataStart + 8, 4).toInt()
            val sizes = (0 until n).map { be(buf, stsz.dataStart + 12 + it * 4, 4) }
            val stsc = child(buf, stbl, "stsc")
            Track(id, handler, offsets, sizes, be(buf, stsc.dataStart + 8 + 4, 4), entry)
        }
    }

    private fun check(name: String) {
        val input = fixture(name)
        val output = File(folder.root, "out_$name")
        Mp4SubtitleMuxer.mux(input, output, events, language = "eng", name = "English")
        System.getenv("MP4_TEST_OUT")?.let { dir -> File(dir).mkdirs(); output.copyTo(File(dir, "out_$name"), overwrite = true) }

        val before = input.readBytes()
        val after = output.readBytes()
        val oldTracks = tracks(before)
        val newTracks = tracks(after)
        assertEquals(oldTracks.size + 1, newTracks.size)

        // The existing tracks still find their data: the first and last chunk are byte-identical.
        oldTracks.forEachIndexed { i, old ->
            val now = newTracks[i]
            assertEquals(old.id, now.id)
            assertEquals(old.sizes, now.sizes)
            listOf(0, old.offsets.lastIndex).forEach { k ->
                val len = minOf(64L, old.sizes.sum() / old.offsets.size).toInt()
                assertArrayEquals(
                    "track ${old.id} chunk $k",
                    before.copyOfRange(old.offsets[k].toInt(), old.offsets[k].toInt() + len),
                    after.copyOfRange(now.offsets[k].toInt(), now.offsets[k].toInt() + len),
                )
            }
        }

        // The new track holds the subtitle text, gaps as empty samples, nothing past the film's end.
        val sub = newTracks.last()
        assertEquals("sbtl", sub.handler)
        assertEquals("tx3g", sub.entry)
        assertEquals(oldTracks.maxOf { it.id } + 1, sub.id)
        assertEquals(1, sub.offsets.size)
        var pos = sub.offsets[0].toInt()
        val texts = sub.sizes.map { size ->
            val len = ((after[pos].toInt() and 0xFF) shl 8) or (after[pos + 1].toInt() and 0xFF)
            assertEquals(size, (len + 2).toLong())
            String(after, pos + 2, len, Charsets.UTF_8).also { pos += size.toInt() }
        }
        assertEquals(listOf("First line", "", "Second\nline", "", "Near the end"), texts)
        assertEquals(after.size, pos)
    }

    @Test
    fun `a faststart mp4 gets a subtitle track and its other tracks still point at their data`() = check("ffmpeg_faststart.mp4")

    @Test
    fun `an mp4 with the movie box last gets a subtitle track`() = check("ffmpeg_moov_last.mp4")

    @Test
    fun `a file that is not an mp4 is refused and leaves nothing behind`() {
        val junk = folder.newFile("junk.mp4").apply { writeBytes(ByteArray(64) { 3 }) }
        val output = File(folder.root, "never.mp4")
        try {
            Mp4SubtitleMuxer.mux(junk, output, events)
            throw AssertionError("expected UnsupportedMp4Exception")
        } catch (_: UnsupportedMp4Exception) {
        }
        assertFalse(output.exists())
        assertTrue(File(folder.root, "never.mp4.part").exists().not())
    }
}
