package com.streamflixreborn.streamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageMatcherTest {

    private fun assertLang(expected: String?, input: String?) =
        assertEquals("normalize(\"$input\")", expected, LanguageMatcher.normalize(input))

    @Test
    fun `english variants`() {
        listOf("English", "english", "eng", "ENG", "en", "EN", "en-US", "en_GB", "English (SDH)",
            "English [CC]", "English - Forced", "Inglés", "Ingles", "Anglais", "Englisch", "Angol",
            "English 2", "eng.srt").forEach { assertLang("en", it) }
    }

    @Test
    fun `hungarian variants`() {
        listOf("Hungarian", "hun", "HUN", "hu", "hu-HU", "Magyar", "magyar", "Hungarian [CC]",
            "Hungarian (Forced)", "Ungarisch").forEach { assertLang("hu", it) }
    }

    @Test
    fun `other languages`() {
        assertLang("es", "Spanish")
        assertLang("es", "Español")
        assertLang("es", "spa")
        assertLang("es", "es-419")
        assertLang("es", "Spanish - Latin")
        assertLang("pt", "pt-BR")
        assertLang("pt", "Português (Brasil)")
        assertLang("de", "ger")
        assertLang("de", "deu")
        assertLang("de", "Deutsch")
        assertLang("fr", "fre")
        assertLang("fr", "Français")
        assertLang("zh", "zh-Hans")
        assertLang("zh", "chi")
        assertLang("it", "Italiano")
        assertLang("ja", "jpn")
        assertLang("he", "iw")
        assertLang("he", "heb")
    }

    @Test
    fun `unknown or undetermined`() {
        listOf(null, "", "  ", "und", "Unknown", "Track 1", "Default", "mul", "Subtitles", "CC", "1080p")
            .forEach { assertLang(null, it) }
    }

    @Test
    fun `detect prefers declared language`() {
        assertEquals("hu", LanguageMatcher.detect("hun", "English"))
        assertEquals("en", LanguageMatcher.detect(null, "English (SDH)"))
        assertEquals("en", LanguageMatcher.detect("und", "English"))
    }

    @Test
    fun `isSpoken treats unknown as spoken`() {
        val spoken = listOf("en", "hu")
        assertTrue(LanguageMatcher.isSpoken("en", spoken))
        assertTrue(LanguageMatcher.isSpoken(null, spoken))
        assertFalse(LanguageMatcher.isSpoken("es", spoken))
    }

    @Test
    fun `iso 639-2 bibliographic codes`() {
        assertEquals("eng", LanguageMatcher.toIso6392B("en"))
        assertEquals("hun", LanguageMatcher.toIso6392B("hu"))
        assertEquals("ger", LanguageMatcher.toIso6392B("de"))
        assertEquals("fre", LanguageMatcher.toIso6392B("fr"))
        assertEquals("spa", LanguageMatcher.toIso6392B("es"))
    }
}
