package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.utils.TMDb3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSuggestionsTest {

    @Test
    fun `add puts the query first, trimmed and de-duplicated ignoring case`() {
        val history = listOf("Dune", "Breaking Bad")
        assertEquals(listOf("breaking bad", "Dune"), SearchHistory.add(history, "  breaking bad "))
        assertEquals(history, SearchHistory.add(history, "   "))
    }

    @Test
    fun `history keeps at most 20 entries, newest first`() {
        var history = emptyList<String>()
        (1..25).forEach { history = SearchHistory.add(history, "q$it") }
        assertEquals(SearchHistory.MAX, history.size)
        assertEquals("q25", history.first())
        assertEquals("q6", history.last())
    }

    @Test
    fun `history json round trip and broken json`() {
        val history = listOf("Dune", "Alien")
        assertEquals(history, SearchHistory.parse(SearchHistory.toJson(history)))
        assertEquals(emptyList<String>(), SearchHistory.parse(null))
        assertEquals(emptyList<String>(), SearchHistory.parse("{oops"))
        assertEquals(listOf("a"), SearchHistory.parse("""["a", null, "A", " "]"""))
    }

    @Test
    fun `suggestions keep movies and shows in order, skip people, cap at the limit`() {
        val results = listOf(
            movie(1, "Dune", "2021-10-22"),
            TMDb3.Person(adult = false, gender = TMDb3.Person.Gender.MALE, id = 2, name = "Denis Villeneuve", popularity = 0f),
            tv(3, "Dune: Prophecy", "2024-11-17"),
            movie(4, "Dune", "2021-10-22"),
            null,
            movie(5, "Dune", null),
        ) + (6..20).map { movie(it, "Dune $it", "1999") }

        val suggestions = SearchSuggestion.fromTmdb(results)

        assertEquals(SearchSuggestion.LIMIT, suggestions.size)
        assertEquals(SearchSuggestion("Dune", "2021", SearchSuggestion.Type.MOVIE), suggestions[0])
        assertEquals(SearchSuggestion("Dune: Prophecy", "2024", SearchSuggestion.Type.TV), suggestions[1])
        assertEquals(SearchSuggestion("Dune", null, SearchSuggestion.Type.MOVIE), suggestions[2])
    }

    @Test
    fun `year needs four digits`() {
        assertEquals("1999", SearchSuggestion.yearOf("1999-01-01"))
        assertNull(SearchSuggestion.yearOf(""))
        assertNull(SearchSuggestion.yearOf("n/a"))
    }

    @Test
    fun `empty box shows recent searches and clear history`() {
        val rows = SearchPanelRow.build("", listOf("Dune", "Alien"), emptyList(), "", "Recent", "Suggestions")
        assertEquals(
            listOf(
                SearchPanelRow.Header("Recent"),
                SearchPanelRow.History("Dune"),
                SearchPanelRow.History("Alien"),
                SearchPanelRow.ClearHistory,
            ),
            rows,
        )
        assertTrue(SearchPanelRow.build("", emptyList(), emptyList(), "", "Recent", "Suggestions").isEmpty())
    }

    @Test
    fun `typing shows matching history and only current suggestions`() {
        val suggestion = SearchSuggestion("Dune", "2021", SearchSuggestion.Type.MOVIE)
        val rows = SearchPanelRow.build("du", listOf("Dune part two", "Alien"), listOf(suggestion), "du", "Recent", "Suggestions")
        assertEquals(
            listOf(
                SearchPanelRow.History("Dune part two"),
                SearchPanelRow.Header("Suggestions"),
                SearchPanelRow.Suggestion(suggestion),
            ),
            rows,
        )
        val stale = SearchPanelRow.build("dun", emptyList(), listOf(suggestion), "du", "Recent", "Suggestions")
        assertTrue(stale.isEmpty())
    }

    private fun movie(id: Int, title: String, date: String?) = TMDb3.Movie(
        posterPath = null, overview = "", releaseDate = date, genresIds = emptyList(), id = id,
        originalTitle = title, originalLanguage = "en", title = title, backdropPath = null,
        popularity = 0f, voteCount = 0, video = false, voteAverage = 0f,
    )

    private fun tv(id: Int, name: String, date: String?) = TMDb3.Tv(
        posterPath = null, popularity = 0f, id = id, backdropPath = null, voteAverage = 0f, overview = "",
        firstAirDate = date, originCountry = emptyList(), genresIds = emptyList(), originalLanguage = "en",
        voteCount = 0, name = name, originalName = name,
    )
}
