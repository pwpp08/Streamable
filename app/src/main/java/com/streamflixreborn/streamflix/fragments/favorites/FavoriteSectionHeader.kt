package com.streamflixreborn.streamflix.fragments.favorites

import com.streamflixreborn.streamflix.adapters.AppAdapter

/** A titled section header in AppAdapter grids. */
data class FavoriteSectionHeader(
    val title: String,
    val key: String,
) : AppAdapter.Item {
    override var itemType: AppAdapter.Type = AppAdapter.Type.FAVORITE_SECTION_HEADER
}
