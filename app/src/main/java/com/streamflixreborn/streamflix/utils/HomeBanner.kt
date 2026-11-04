package com.streamflixreborn.streamflix.utils

/** Values of [UserPreferences.homeBanner]. */
object HomeBanner {
    const val TRENDING = "trending"
    const val OFF = "off"
    const val FAVORITES = "favorites"
    const val RECENT = "recent"
    const val ROW_PREFIX = "row:"

    fun row(name: String) = ROW_PREFIX + name

    fun rowName(value: String): String? = value.takeIf { it.startsWith(ROW_PREFIX) }?.removePrefix(ROW_PREFIX)?.takeIf { it.isNotEmpty() }

    /** Most titles the banner carousel shows. */
    const val MAX_ITEMS = 8
}
