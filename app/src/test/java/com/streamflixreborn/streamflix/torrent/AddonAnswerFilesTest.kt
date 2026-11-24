package com.streamflixreborn.streamflix.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AddonAnswerFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun streams() = listOf(
        AddonStream(direct = AddonStream.Direct("https://cdn.example.com/a.m3u8", 1080, "Cinejoy Sirius 1080p", mapOf("Referer" to "https://site.example/"), measured = true)),
        AddonStream(direct = AddonStream.Direct("https://cdn.example.com/b.m3u8", 720, "Light", emptyMap())),
    )

    @Test
    fun `an answer survives being saved and loaded`() {
        val store = AddonAnswerFiles(folder.newFolder("answers"))
        store.save("https://addon.example/secret/stream/movie/tt1.json", 1234L, streams())
        val (at, loaded) = store.load("https://addon.example/secret/stream/movie/tt1.json")!!
        assertEquals(1234L, at)
        assertEquals(2, loaded.size)
        assertEquals("Referer", loaded[0].direct!!.headers.keys.single())
        assertEquals(1080, loaded[0].direct!!.height)
        assertTrue(loaded[0].direct!!.measured)
    }

    @Test
    fun `nothing is loaded for a request that was never saved`() {
        val store = AddonAnswerFiles(folder.newFolder("answers"))
        assertNull(store.load("https://addon.example/stream/movie/tt9.json"))
    }

    @Test
    fun `the addon link is not written into file names or contents`() {
        val dir = folder.newFolder("answers")
        val url = "https://addon.example/very-secret-token/stream/movie/tt1.json"
        AddonAnswerFiles(dir).save(url, 1L, streams())
        val files = dir.listFiles()!!
        assertEquals(1, files.size)
        assertFalse(files[0].name.contains("secret"))
        assertFalse(files[0].readText().contains("very-secret-token"))
        assertNotNull(AddonAnswerFiles(dir).load(url))
    }

    @Test
    fun `a damaged file is ignored`() {
        val dir = folder.newFolder("answers")
        val store = AddonAnswerFiles(dir)
        store.save("https://addon.example/x.json", 1L, streams())
        dir.listFiles()!!.single().writeText("{ not json")
        assertNull(store.load("https://addon.example/x.json"))
    }

    @Test
    fun `a deleted answer is gone`() {
        val store = AddonAnswerFiles(folder.newFolder("answers"))
        store.save("https://addon.example/y.json", 1L, streams())
        store.delete("https://addon.example/y.json")
        assertNull(store.load("https://addon.example/y.json"))
    }

    @Test
    fun `a torrent stream with every field survives too`() {
        val dir = folder.newFolder("answers")
        val torrent = com.streamflixreborn.streamflix.torrent.TorrentStream(
            infoHash = "a".repeat(40), fileIdx = 3, release = "Film.2020.1080p.WEB-DL.x264", fileName = "film.mkv",
            seeders = 42, sizeBytes = 3L shl 30, site = "YTS", languages = "GB", height = 1080,
            trackers = listOf("udp://tracker.example:1337/announce"),
        )
        val store = AddonAnswerFiles(dir)
        store.save("https://addon.example/t.json", 5L, listOf(AddonStream(torrent = torrent), AddonStream(torrent = torrent.copy(fileIdx = null, fileName = null))))
        val (_, loaded) = store.load("https://addon.example/t.json")!!
        assertEquals(torrent, loaded[0].torrent)
        assertEquals(null, loaded[1].torrent!!.fileIdx)
        assertEquals(null, loaded[1].torrent!!.fileName)
    }

    @Test
    fun `files in the old format are ignored and cleaned away`() {
        val dir = folder.newFolder("answers")
        java.io.File(dir, "0123456789abcdef0123456789abcdef.json").writeText("{\"a\":1,\"b\":[{\"a\":{}}]}")
        val store = AddonAnswerFiles(dir)
        store.save("https://addon.example/new.json", 1L, streams())
        assertEquals(1, dir.listFiles()!!.size)
        assertTrue(dir.listFiles()!!.single().name.endsWith(".v2.json"))
    }
}
