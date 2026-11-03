package com.streamflixreborn.streamflix.fragments.home

import com.streamflixreborn.streamflix.fragments.home.HomeRowsConfig.Companion.CONTINUE_WATCHING
import com.streamflixreborn.streamflix.fragments.home.HomeRowsConfig.Companion.FAVORITE_MOVIES
import com.streamflixreborn.streamflix.fragments.home.HomeRowsConfig.Companion.FAVORITE_TV_SHOWS
import com.streamflixreborn.streamflix.fragments.home.HomeRowsConfig.Companion.RECENTLY_WATCHED
import com.streamflixreborn.streamflix.fragments.home.HomeRowsConfig.Companion.providerKey
import com.streamflixreborn.streamflix.models.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowsConfigTest {

    private val library = listOf(CONTINUE_WATCHING, RECENTLY_WATCHED, FAVORITE_MOVIES, FAVORITE_TV_SHOWS)

    @Test
    fun `default lists library rows then provider rows, all visible`() {
        val config = HomeRowsConfig.default(listOf(Category.FEATURED, "Trending", "Popular"))
        assertEquals(library + listOf(providerKey("Trending"), providerKey("Popular")), config.keys)
        assertTrue(config.rows.all { it.visible })
    }

    @Test
    fun `merge appends new provider rows visible and keeps the stored order`() {
        val stored = HomeRowsConfig(
            listOf(
                HomeRowsConfig.Row(providerKey("Trending"), visible = false),
                HomeRowsConfig.Row(CONTINUE_WATCHING),
                HomeRowsConfig.Row(RECENTLY_WATCHED),
                HomeRowsConfig.Row(FAVORITE_MOVIES),
                HomeRowsConfig.Row(FAVORITE_TV_SHOWS),
            )
        )
        val merged = stored.merge(listOf("Trending", "New releases"))
        assertEquals(
            listOf(providerKey("Trending")) + library + providerKey("New releases"),
            merged.keys,
        )
        assertFalse(merged.isVisible(providerKey("Trending")))
        assertTrue(merged.isVisible(providerKey("New releases")))
    }

    @Test
    fun `merge drops provider rows that no longer exist and restores missing library rows`() {
        val stored = HomeRowsConfig(
            listOf(HomeRowsConfig.Row(providerKey("Gone")), HomeRowsConfig.Row(FAVORITE_MOVIES, visible = false))
        )
        val merged = stored.merge(listOf("Trending"))
        assertEquals(
            listOf(FAVORITE_MOVIES, CONTINUE_WATCHING, RECENTLY_WATCHED, FAVORITE_TV_SHOWS, providerKey("Trending")),
            merged.keys,
        )
        assertFalse(merged.isVisible(FAVORITE_MOVIES))
    }

    @Test
    fun `fromJson drops unknown and duplicate keys`() {
        val json = """{"version":1,"rows":[
            {"key":"recently_watched","visible":false},
            {"key":"bogus","visible":true},
            {"key":"provider:","visible":true},
            {"key":"provider:Trending"},
            {"key":"recently_watched","visible":true},
            null,
            {"visible":true}
        ]}"""
        val config = HomeRowsConfig.fromJson(json)!!
        assertEquals(listOf(RECENTLY_WATCHED, providerKey("Trending")), config.keys)
        assertFalse(config.isVisible(RECENTLY_WATCHED))
        assertTrue(config.isVisible(providerKey("Trending")))
    }

    @Test
    fun `fromJson returns null for missing or broken json`() {
        assertNull(HomeRowsConfig.fromJson(null))
        assertNull(HomeRowsConfig.fromJson(""))
        assertNull(HomeRowsConfig.fromJson("{not json"))
        assertNull(HomeRowsConfig.fromJson("[1,2]"))
    }

    @Test
    fun `json round trip`() {
        val config = HomeRowsConfig.default(listOf("Trending")).withVisible(CONTINUE_WATCHING, false).moved(4, 0)
        assertEquals(config, HomeRowsConfig.fromJson(config.toJson()))
    }

    @Test
    fun `apply orders rows, hides hidden ones, keeps featured first and new rows last`() {
        val config = HomeRowsConfig(
            listOf(
                HomeRowsConfig.Row(providerKey("Popular")),
                HomeRowsConfig.Row(CONTINUE_WATCHING),
                HomeRowsConfig.Row(RECENTLY_WATCHED, visible = false),
                HomeRowsConfig.Row(FAVORITE_MOVIES),
                HomeRowsConfig.Row(FAVORITE_TV_SHOWS),
            )
        )
        val names = listOf(
            Category.FEATURED,
            Category.CONTINUE_WATCHING,
            Category.RECENTLY_WATCHED,
            Category.FAVORITE_MOVIES,
            Category.FAVORITE_TV_SHOWS,
            "Trending",
            "Popular",
        )
        val result = config.apply(names) { HomeRowsConfig.keyForCategory(it) }
        assertEquals(
            listOf(
                Category.FEATURED,
                "Popular",
                Category.CONTINUE_WATCHING,
                Category.FAVORITE_MOVIES,
                Category.FAVORITE_TV_SHOWS,
                "Trending",
            ),
            result,
        )
    }

    @Test
    fun `moved ignores out of range indices`() {
        val config = HomeRowsConfig.default(emptyList())
        assertEquals(config, config.moved(0, 10))
        assertEquals(
            listOf(RECENTLY_WATCHED, CONTINUE_WATCHING, FAVORITE_MOVIES, FAVORITE_TV_SHOWS),
            config.moved(0, 1).keys,
        )
    }
}
