package com.streamflixreborn.streamflix.fragments.home

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.streamflixreborn.streamflix.models.Category

/**
 * Order and visibility of the Home rows, stored as JSON in `UserPreferences.homeRowsConfig`.
 *
 * Keys: the four library rows ([CONTINUE_WATCHING], [RECENTLY_WATCHED], [FAVORITE_MOVIES],
 * [FAVORITE_TV_SHOWS]) and `provider:<category name>` for the provider's own rows. The featured
 * carousel is not configurable and always stays on top.
 *
 * JSON: `{"version":1,"rows":[{"key":"continue_watching","visible":true}, ...]}`
 */
data class HomeRowsConfig(val rows: List<Row>) {

    data class Row(val key: String, val visible: Boolean = true)

    val keys: List<String> get() = rows.map { it.key }

    fun isVisible(key: String): Boolean = rows.firstOrNull { it.key == key }?.visible ?: true

    /**
     * This config against the rows that exist now: library rows are always present, provider
     * rows not in [providerCategories] are dropped, new provider rows are appended (visible).
     */
    fun merge(providerCategories: List<String>): HomeRowsConfig {
        val available = availableKeys(providerCategories)
        val kept = rows.filter { it.key in available }.distinctBy { it.key }
        val keptKeys = kept.map { it.key }.toSet()
        return HomeRowsConfig(kept + available.filter { it !in keptKeys }.map { Row(it) })
    }

    /**
     * Orders [items] by this config and removes hidden ones. Items whose [keyOf] is null (the
     * featured row) stay first; items without a row here (new provider rows) go last, visible.
     */
    fun <T> apply(items: List<T>, keyOf: (T) -> String?): List<T> {
        val pinned = items.filter { keyOf(it) == null }
        val configurable = items.filter { keyOf(it) != null }
        val byKey = configurable.groupBy { keyOf(it)!! }
        val known = rows.map { it.key }.toSet()
        val ordered = rows.filter { it.visible }.flatMap { byKey[it.key].orEmpty() }
        val unconfigured = configurable.filter { keyOf(it) !in known }
        return pinned + ordered + unconfigured
    }

    fun withVisible(key: String, visible: Boolean): HomeRowsConfig =
        copy(rows = rows.map { if (it.key == key) it.copy(visible = visible) else it })

    fun moved(from: Int, to: Int): HomeRowsConfig {
        if (from !in rows.indices || to !in rows.indices || from == to) return this
        val list = rows.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(rows = list)
    }

    fun toJson(): String = gson.toJson(Stored(VERSION, rows.map { StoredRow(it.key, it.visible) }))

    private data class Stored(val version: Int?, val rows: List<StoredRow?>?)
    private data class StoredRow(val key: String?, val visible: Boolean?)

    companion object {
        const val CONTINUE_WATCHING = "continue_watching"
        const val RECENTLY_WATCHED = "recently_watched"
        const val FAVORITE_MOVIES = "favorite_movies"
        const val FAVORITE_TV_SHOWS = "favorite_tv_shows"
        const val PROVIDER_PREFIX = "provider:"

        val LIBRARY_KEYS = listOf(CONTINUE_WATCHING, RECENTLY_WATCHED, FAVORITE_MOVIES, FAVORITE_TV_SHOWS)

        private const val VERSION = 1
        private val gson = Gson()

        fun providerKey(categoryName: String): String = PROVIDER_PREFIX + categoryName

        fun isProviderKey(key: String): Boolean = key.startsWith(PROVIDER_PREFIX) && key.length > PROVIDER_PREFIX.length

        fun providerCategoryName(key: String): String? = key.takeIf(::isProviderKey)?.removePrefix(PROVIDER_PREFIX)

        fun isValidKey(key: String): Boolean = key in LIBRARY_KEYS || isProviderKey(key)

        /** The key of a Home category as HomeViewModel builds it; null for the featured row. */
        fun keyForCategory(name: String): String? = when (name) {
            Category.FEATURED -> null
            Category.CONTINUE_WATCHING -> CONTINUE_WATCHING
            Category.RECENTLY_WATCHED -> RECENTLY_WATCHED
            Category.FAVORITE_MOVIES -> FAVORITE_MOVIES
            Category.FAVORITE_TV_SHOWS -> FAVORITE_TV_SHOWS
            else -> providerKey(name)
        }

        /** Library rows first, then the provider rows in the provider's order, all visible. */
        fun default(providerCategories: List<String>): HomeRowsConfig =
            HomeRowsConfig(availableKeys(providerCategories).map { Row(it) })

        /** Null when [json] is missing or unreadable. Unknown and duplicate keys are dropped. */
        fun fromJson(json: String?): HomeRowsConfig? {
            if (json.isNullOrBlank()) return null
            val stored = try {
                gson.fromJson(json, Stored::class.java)
            } catch (_: JsonParseException) {
                null
            } catch (_: IllegalStateException) {
                null
            } ?: return null
            val rows = stored.rows.orEmpty()
                .mapNotNull { row ->
                    val key = row?.key?.takeIf(::isValidKey) ?: return@mapNotNull null
                    Row(key, row.visible ?: true)
                }
                .distinctBy { it.key }
            return HomeRowsConfig(rows)
        }

        private fun availableKeys(providerCategories: List<String>): List<String> =
            LIBRARY_KEYS + providerCategories
                .filter { it != Category.FEATURED && keyForCategory(it) !in LIBRARY_KEYS }
                .map(::providerKey)
                .distinct()
    }
}
