package com.streamflixreborn.streamflix.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioAddonsTest {

    @Test
    fun `links are normalized and torrentio is left to the built-in source`() {
        assertEquals("https://pengu.uk/abc", StremioAddons.normalize("stremio://pengu.uk/abc/manifest.json"))
        assertEquals("https://x.example.com/cfg", StremioAddons.normalize(" https://x.example.com/cfg/manifest.json "))
        assertNull(StremioAddons.normalize("https://torrentio.strem.fun/sort=qualitysize/manifest.json"))
        assertNull(StremioAddons.normalize("not a link"))
        assertEquals(2, StremioAddons.configured("https://a.example.com/x\n\nstremio://b.example.com/y/manifest.json\nhttps://a.example.com/x").size)
    }

    @Test
    fun `sign-in placeholders are not servers`() {
        val json = """{"streams":[{"name":"PenguPlay","title":"You must sign in","description":"Your PenguPlay authentication is missing","url":"https://pengu.uk/signin.mp4"}]}"""
        assertTrue(AddonStreamParser.parse(json).isEmpty())
    }

    @Test
    fun `direct links keep quality and headers, hardcoded subs are dropped`() {
        val json = """{"streams":[
          {"name":"PenguPlay\n1080p","title":"Inception WEB-DL","url":"https://cdn.example.com/a.m3u8","behaviorHints":{"proxyHeaders":{"request":{"Referer":"https://site.example/"}}}},
          {"name":"PenguPlay\n1080p","title":"Inception HC Spanish","url":"https://cdn.example.com/b.m3u8"},
          {"name":"PenguPlay\n480p","title":"Inception","url":"https://cdn.example.com/c.mp4"}
        ]}"""
        val servers = StremioAddons.toServers("PenguPlay", AddonStreamParser.parse(json), torrentsAllowed = true)
        assertEquals(1, servers.size)
        assertEquals("PenguPlay 1080p", servers[0].name)
        val video = StremioAddons.videoFor(servers[0])
        assertEquals("https://site.example/", video.headers?.get("Referer"))
        assertEquals("application/x-mpegURL", video.type)
    }

    @Test
    fun `torrents from other addons go through the usual 1080p rules`() {
        val hash = "b".repeat(40)
        val json = """{"streams":[
          {"name":"MediaFusion 1080p","description":"Inception.2010.1080p.BluRay.x264\n💾 4.5 GB 👤 120","infoHash":"$hash","fileIdx":0},
          {"name":"MediaFusion 2160p","description":"Inception.2010.2160p.UHD.x265\n💾 40 GB 👤 300","infoHash":"${"c".repeat(40)}"}
        ]}"""
        val servers = StremioAddons.toServers("MediaFusion", AddonStreamParser.parse(json), torrentsAllowed = true)
        assertEquals(1, servers.size)
        assertTrue(servers[0].name.contains("MediaFusion"))
        assertTrue(servers[0].id.contains(hash))
        assertTrue(StremioAddons.toServers("MediaFusion", AddonStreamParser.parse(json), torrentsAllowed = false).isEmpty())
    }
}
