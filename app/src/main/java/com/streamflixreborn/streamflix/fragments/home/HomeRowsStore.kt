package com.streamflixreborn.streamflix.fragments.home

import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Reads and writes [HomeRowsConfig] in the active profile's preferences. */
object HomeRowsStore {
    private val _changes = MutableStateFlow(0)

    /** Bumped on every save, so Home re-applies the config. */
    val changes: StateFlow<Int> = _changes

    fun load(): HomeRowsConfig? = runCatching { HomeRowsConfig.fromJson(UserPreferences.homeRowsConfig) }.getOrNull()

    fun save(config: HomeRowsConfig) {
        UserPreferences.homeRowsConfig = config.toJson()
        _changes.value++
    }

    fun reset() {
        UserPreferences.homeRowsConfig = null
        _changes.value++
    }
}
