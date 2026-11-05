package com.streamflixreborn.streamflix.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.streamflixreborn.streamflix.models.Video

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Movies : Screen
    data object Shows : Screen
    data object Queue : Screen
    data object Settings : Screen
    data class MovieDetails(val id: String) : Screen
    data class ShowDetails(val id: String) : Screen
    data class Genre(val id: String, val name: String) : Screen
    data class Person(val id: String, val name: String) : Screen
}

/** What the player was asked to play; [startMs] resumes. */
data class PlayRequest(
    val videoType: Video.Type,
    val title: String,
    val subtitle: String? = null,
    val startMs: Long = 0,
)

/** App-wide navigation: one back stack per top-level tab, plus the player on top of everything. */
object Nav {
    val tabs = listOf(Screen.Home, Screen.Search, Screen.Movies, Screen.Shows, Screen.Queue, Screen.Settings)

    var tab by mutableStateOf<Screen>(Screen.Home)
        private set

    private val stacks = tabs.associateWith { mutableStateListOf(it) }

    val stack get() = stacks.getValue(tab)
    val current: Screen get() = stack.last()
    val canGoBack get() = stack.size > 1

    var playing by mutableStateOf<PlayRequest?>(null)

    fun selectTab(screen: Screen) {
        if (tab == screen) {
            // Re-selecting a tab returns to its root.
            val s = stacks.getValue(screen)
            while (s.size > 1) s.removeAt(s.lastIndex)
        }
        tab = screen
    }

    fun open(screen: Screen) {
        if (stack.last() != screen) stack.add(screen)
    }

    fun back(): Boolean {
        if (playing != null) {
            playing = null
            return true
        }
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        if (tab != Screen.Home) {
            tab = Screen.Home
            return true
        }
        return false
    }

    fun play(request: PlayRequest) {
        playing = request
    }
}
