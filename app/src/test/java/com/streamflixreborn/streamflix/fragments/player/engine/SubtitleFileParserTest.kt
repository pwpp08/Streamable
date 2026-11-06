package com.streamflixreborn.streamflix.fragments.player.engine

import com.streamflixreborn.streamflix.utils.OpenSubtitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFileParserTest {

    private val srt = """
        1
        00:00:01,000 --> 00:00:03,500
        Hello <i>there</i>.

        2
        00:00:04,000 --> 00:00:06,000
        {\an8}Top line
        second line

        3
        00:01:00,250 --> 00:01:02,000
        Later
    """.trimIndent()

    @Test
    fun parsesSrtBlocksWithTagsAndMultipleLines() {
        val lines = SubtitleFileParser.parse(srt)
        assertEquals(3, lines.size)
        assertEquals(1_000L, lines[0].startMs)
        assertEquals(3_500L, lines[0].endMs)
        assertEquals("Hello <i>there</i>.", lines[0].text)
        assertEquals("Top line\nsecond line", lines[1].text)
        assertEquals(60_250L, lines[2].startMs)
    }

    @Test
    fun parsesWebVttWithHeaderAndNoHours() {
        val vtt = "WEBVTT\n\nNOTE a comment\n\n00:01.500 --> 00:03.000 align:start\nHi\n\n1:02:03.4 --> 1:02:05.000\nHour\n"
        val lines = SubtitleFileParser.parse(vtt)
        assertEquals(2, lines.size)
        assertEquals(1_500L, lines[0].startMs)
        assertEquals("Hi", lines[0].text)
        assertEquals(3_723_400L, lines[1].startMs)
    }

    @Test
    fun windowsLineEndingsAndBadBlocksAreHandled() {
        val text = "1\r\n00:00:01,000 --> 00:00:02,000\r\nOne\r\n\r\ngarbage\r\n\r\n3\r\n00:00:05,000 --> 00:00:04,000\r\nBackwards\r\n"
        val lines = SubtitleFileParser.parse(text)
        assertEquals(1, lines.size)
        assertEquals("One", lines[0].text)
    }

    @Test
    fun activeAtFindsOverlappingAndOnlyCurrentLines() {
        val lines = SubtitleFileParser.parse(srt)
        assertTrue(SubtitleFileParser.activeAt(lines, 500).isEmpty())
        assertEquals("Hello <i>there</i>.", SubtitleFileParser.activeAt(lines, 1_000).single().text)
        assertTrue(SubtitleFileParser.activeAt(lines, 3_600).isEmpty())
        assertEquals("Later", SubtitleFileParser.activeAt(lines, 61_000).single().text)
    }

    @Test
    fun activeAtShowsTwoCuesThatOverlap() {
        val lines = SubtitleFileParser.parse("00:00:01,000 --> 00:00:05,000\nA\n\n00:00:03,000 --> 00:00:06,000\nB\n")
        assertEquals(listOf("A", "B"), SubtitleFileParser.activeAt(lines, 4_000).map { it.text })
    }

    @Test
    fun utf8IsKeptAndLegacyBytesFallBackToWindows1252() {
        val utf8 = "árvíztűrő".toByteArray(Charsets.UTF_8)
        assertEquals("árvíztűrő", SubtitleFileParser.decode(utf8))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "ok".toByteArray()
        assertEquals("ok", SubtitleFileParser.decode(bom))
        val legacy = byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte()) // "café" in Windows-1252
        assertEquals("café", SubtitleFileParser.decode(legacy))
    }

    @Test
    fun parseTimeRejectsGarbage() {
        assertNull(SubtitleFileParser.parseTime("nope"))
        assertEquals(62_345L, SubtitleFileParser.parseTime("01:02,345"))
    }

    private fun sub(language: String, downloads: String, hearing: String = "0", bad: String = "0", name: String = "x.srt") =
        OpenSubtitles.Subtitle(
            subFileName = name,
            subLanguageID = language,
            subDownloadsCnt = downloads,
            subHearingImpaired = hearing,
            subBad = bad,
            subDownloadLink = "https://example.com/$downloads.gz",
        )

    @Test
    fun autoPickPrefersFirstSpokenLanguageThenPopularNormalSubtitles() {
        val picked = AutoSubtitleFetch.pick(
            listOf(
                sub("hun", "10"),
                sub("eng", "50", hearing = "1"),
                sub("eng", "20"),
                sub("eng", "999", bad = "1"),
            ),
            listOf("en", "hu"),
        )
        assertEquals("20", picked?.subDownloadsCnt)
    }

    @Test
    fun autoPickFallsToNextLanguageAndReturnsNullWhenNothingFits() {
        val onlyHungarian = listOf(sub("hun", "5"))
        assertEquals("5", AutoSubtitleFetch.pick(onlyHungarian, listOf("en", "hu"))?.subDownloadsCnt)
        assertNull(AutoSubtitleFetch.pick(onlyHungarian, listOf("en")))
        assertNull(AutoSubtitleFetch.pick(emptyList(), listOf("en")))
    }
}
