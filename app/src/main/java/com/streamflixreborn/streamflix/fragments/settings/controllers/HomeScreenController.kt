package com.streamflixreborn.streamflix.fragments.settings.controllers

import com.streamflixreborn.streamflix.R

/** Settings > Home screen: opens the row order / visibility screen. */
class HomeScreenController(private val host: SettingsHost) : SettingsController {

    override fun bind() {
        host.onClick(KEY_HOME) { host.navigate(R.id.home_rows) }
    }

    private companion object {
        const val KEY_HOME = "screen_home"
    }
}
