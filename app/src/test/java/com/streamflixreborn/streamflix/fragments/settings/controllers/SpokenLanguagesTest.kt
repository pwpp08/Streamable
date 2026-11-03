package com.streamflixreborn.streamflix.fragments.settings.controllers

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenLanguagesTest {

    @Test
    fun `checking appends in selection order`() {
        var order = listOf("en", "hu")
        order = SpokenLanguages.toggle(order, "de", true)
        order = SpokenLanguages.toggle(order, "en", false)
        order = SpokenLanguages.toggle(order, "fr", true)
        assertEquals(listOf("hu", "de", "fr"), order)
    }

    @Test
    fun `make primary moves to front`() {
        assertEquals(listOf("de", "en", "hu"), SpokenLanguages.makePrimary(listOf("en", "hu", "de"), "de"))
        assertEquals(listOf("ja", "en"), SpokenLanguages.makePrimary(listOf("en"), "ja"))
    }

    @Test
    fun `sanitize normalises and falls back to default`() {
        assertEquals(listOf("en", "hu"), SpokenLanguages.sanitize(listOf(" EN", "hu", "en", "")))
        assertEquals(SpokenLanguages.default, SpokenLanguages.sanitize(emptyList()))
    }

    @Test
    fun `summary marks the first language`() {
        val text = SpokenLanguages.summary(listOf("hu", "en"), { it.uppercase() }) { "$it*" }
        assertEquals("HU*, EN", text)
    }

    @Test
    fun `picker offers the requested languages`() {
        assertEquals(22, SpokenLanguages.choices.size)
        assertEquals(SpokenLanguages.choices.size, SpokenLanguages.choices.distinct().size)
    }
}
