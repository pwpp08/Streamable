package com.streamflixreborn.streamflix.fragments.player.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioGateTest {

    private fun master(vararg audio: String) = buildString {
        appendLine("#EXTM3U")
        audio.forEach { appendLine("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",$it") }
        appendLine("#EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,AUDIO=\"a\"")
        appendLine("v.m3u8")
    }

    @Test
    fun onlyForeignDubsAreFlagged() {
        val dubs = master("LANGUAGE=\"fr\",NAME=\"French\"", "LANGUAGE=\"it\",NAME=\"Italian\"")
        assertTrue(AudioGate.lacksSpokenAudio(dubs, listOf("en", "hu")))
    }

    @Test
    fun spokenOrUnknownAudioIsFine() {
        val english = master("LANGUAGE=\"fr\",NAME=\"French\"", "LANGUAGE=\"en\",NAME=\"English\"")
        assertFalse(AudioGate.lacksSpokenAudio(english, listOf("en", "hu")))
        val unknown = master("NAME=\"Track 1\"", "LANGUAGE=\"fr\",NAME=\"French\"")
        assertFalse(AudioGate.lacksSpokenAudio(unknown, listOf("en")))
        assertFalse(AudioGate.lacksSpokenAudio("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n", listOf("en")))
    }
}
