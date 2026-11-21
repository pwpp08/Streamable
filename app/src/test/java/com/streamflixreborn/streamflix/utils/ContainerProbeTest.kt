package com.streamflixreborn.streamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class ContainerProbeTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun mkv(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        // EBML header (4 bytes of body), then a Segment of unknown size.
        out.write(bytes(0x1A, 0x45, 0xDF, 0xA3, 0x84, 0x42, 0x86, 0x81, 0x01))
        out.write(bytes(0x18, 0x53, 0x80, 0x67, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF))
        // Tracks > TrackEntry > Video > PixelWidth, PixelHeight
        out.write(bytes(0x16, 0x54, 0xAE, 0x6B, 0x8C))
        out.write(bytes(0xAE, 0x8A))
        out.write(bytes(0xE0, 0x88))
        out.write(bytes(0xB0, 0x82, width shr 8, width and 0xFF))
        out.write(bytes(0xBA, 0x82, height shr 8, height and 0xFF))
        return out.toByteArray()
    }

    private fun be32(v: Long) = bytes(((v shr 24) and 0xFF).toInt(), ((v shr 16) and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt())

    private fun tkhd(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(be32(92))
        out.write("tkhd".toByteArray())
        out.write(ByteArray(76))
        out.write(be32(width.toLong() shl 16))
        out.write(be32(height.toLong() shl 16))
        return out.toByteArray()
    }

    private fun mp4(vararg tracks: Pair<Int, Int>): ByteArray {
        val body = ByteArrayOutputStream()
        tracks.forEach { body.write(tkhd(it.first, it.second)) }
        val out = ByteArrayOutputStream()
        out.write("....ftypisom".toByteArray())
        out.write(be32(8L + body.size()))
        out.write("moov".toByteArray())
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    @Test fun readsMatroskaPictureSize() {
        assertEquals(1080, ContainerProbe.pictureHeight(mkv(1920, 1080)))
        assertEquals(720, ContainerProbe.pictureHeight(mkv(1280, 720)))
    }

    @Test fun letterboxedMatroskaCountsByWidth() {
        assertEquals(1080, ContainerProbe.pictureHeight(mkv(1920, 800)))
    }

    @Test fun readsMp4PictureSizeAndIgnoresAudioTracks() {
        assertEquals(1080, ContainerProbe.pictureHeight(mp4(0 to 0, 1920 to 1080)))
        assertEquals(720, ContainerProbe.pictureHeight(mp4(1280 to 720)))
    }

    @Test fun unreadableDataGivesNull() {
        assertNull(ContainerProbe.pictureHeight(ByteArray(64)))
        assertNull(ContainerProbe.mkvHeight(bytes(0x1A, 0x45, 0xDF, 0xA3)))
    }
}
