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
}
