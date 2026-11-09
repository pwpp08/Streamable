package com.streamflixreborn.streamflix.fragments.search

import com.streamflixreborn.streamflix.utils.CinemaStatus
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.streamflixreborn.streamflix.utils.TMDb3

/** Recent searches: newest first, at most [MAX], de-duplicated ignoring case. */
object SearchHistory {
    const val MAX = 20
    private val gson = Gson()

    fun parse(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val array = try {
            gson.fromJson(json, Array<String?>::class.java)
        } catch (_: JsonParseException) {
            null
        } catch (_: IllegalStateException) {
            null
        } ?: return emptyList()
        return normalize(array.filterNotNull())
    }

    fun toJson(history: List<String>): String = gson.toJson(normalize(history))

    /** [query] moved (or added) to the front. Blank queries leave the history unchanged. */
    fun add(history: List<String>, query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return normalize(history)
        return normalize(listOf(trimmed) + history)
    }

    /** Entries containing [text] (ignoring case), at most [limit]. */
    fun matching(history: List<String>, text: String, limit: Int): List<String> {
        val needle = text.trim()
        if (needle.isEmpty()) return emptyList()
        return history.filter { it.contains(needle, ignoreCase = true) && !it.equals(needle, ignoreCase = true) }
            .take(limit)
    }

    private fun normalize(history: List<String>): List<String> = history
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .take(MAX)
}

/** A TMDb `search/multi` hit shown while typing. */
data class SearchSuggestion(val title: String, val year: String?, val type: Type) {
    enum class Type { MOVIE, TV }

    /** TMDb id and release date, kept apart from the data so two suggestions still compare by what they show. */
    var tmdbId: String? = null
    var releasedMillis: Long? = null

    companion object {
        const val MIN_CHARS = 2
        const val LIMIT = 8
        const val DEBOUNCE_MS = 300L

        /** Movies and shows from [results] in TMDb's order (people skipped), at most [limit]. */
        fun fromTmdb(results: List<TMDb3.MultiItem?>, limit: Int = LIMIT): List<SearchSuggestion> =
            results.mapNotNull { item ->
                when (item) {
                    is TMDb3.Movie -> item.title.takeIf { it.isNotBlank() }
                        ?.let {
                            SearchSuggestion(it.trim(), yearOf(item.releaseDate), Type.MOVIE).also { found ->
                                found.tmdbId = item.id.toString()
                                found.releasedMillis = CinemaStatus.parseDate(item.releaseDate)
                            }
                        }
                    is TMDb3.Tv -> item.name.takeIf { it.isNotBlank() }
                        ?.let { SearchSuggestion(it.trim(), yearOf(item.firstAirDate), Type.TV) }
                    else -> null
                }
            }
                .distinctBy { Triple(it.title.lowercase(), it.year, it.type) }
                .take(limit)

        fun yearOf(date: String?): String? = date?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
    }
}

/** What the list under the search box shows. */
sealed interface SearchPanelRow {
    data class Header(val title: String) : SearchPanelRow
    data class History(val query: String) : SearchPanelRow
    data object ClearHistory : SearchPanelRow
    data class Suggestion(val suggestion: SearchSuggestion) : SearchPanelRow

    companion object {
        private const val MATCHING_HISTORY = 3

        /**
         * Empty box: recent searches and "Clear history". While typing: matching recent searches,
         * then the suggestions for [text] ([suggestionsFor] tells which text they were fetched for;
         * stale ones are ignored).
         */
        fun build(
            text: String,
            history: List<String>,
            suggestions: List<SearchSuggestion>,
            suggestionsFor: String,
            recentTitle: String,
            suggestionsTitle: String,
        ): List<SearchPanelRow> {
            val query = text.trim()
            if (query.isEmpty()) {
                if (history.isEmpty()) return emptyList()
                return listOf(Header(recentTitle)) + history.map(::History) + ClearHistory
            }
            val matching = SearchHistory.matching(history, query, MATCHING_HISTORY).map(::History)
            val current = if (suggestionsFor.trim().equals(query, ignoreCase = true)) suggestions else emptyList()
            val suggestionRows = current.map(::Suggestion)
            return buildList {
                addAll(matching)
                if (suggestionRows.isNotEmpty()) {
                    add(Header(suggestionsTitle))
                    addAll(suggestionRows)
                }
            }
        }
    }
}
