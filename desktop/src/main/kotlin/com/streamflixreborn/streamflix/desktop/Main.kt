package com.streamflixreborn.streamflix.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.streamflixreborn.streamflix.desktop.data.Library
import com.streamflixreborn.streamflix.desktop.player.PlayerScreen
import com.streamflixreborn.streamflix.desktop.theme.StreamFlixTheme
import com.streamflixreborn.streamflix.desktop.ui.GenreScreen
import com.streamflixreborn.streamflix.desktop.ui.HomeScreen
import com.streamflixreborn.streamflix.desktop.ui.MovieDetailsScreen
import com.streamflixreborn.streamflix.desktop.ui.MoviesScreen
import com.streamflixreborn.streamflix.desktop.ui.PersonScreen
import com.streamflixreborn.streamflix.desktop.ui.QueueScreen
import com.streamflixreborn.streamflix.desktop.ui.SearchScreen
import com.streamflixreborn.streamflix.desktop.ui.SettingsScreen
import com.streamflixreborn.streamflix.desktop.ui.ShowDetailsScreen
import com.streamflixreborn.streamflix.desktop.ui.ShowsScreen
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.UserPreferences

private data class TabSpec(val screen: Screen, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabSpecs = listOf(
    TabSpec(Screen.Home, "Home", Icons.Outlined.Home, Icons.Rounded.Home),
    TabSpec(Screen.Search, "Search", Icons.Outlined.Search, Icons.Rounded.Search),
    TabSpec(Screen.Movies, "Movies", Icons.Outlined.Movie, Icons.Rounded.Movie),
    TabSpec(Screen.Shows, "Shows", Icons.Outlined.Tv, Icons.Rounded.Tv),
    TabSpec(Screen.Queue, "Queue", Icons.Outlined.VideoLibrary, Icons.Rounded.VideoLibrary),
    TabSpec(Screen.Settings, "Settings", Icons.Outlined.Settings, Icons.Rounded.Settings),
)

fun main() {
    // A crash leaves a trace in the data folder instead of vanishing.
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        runCatching {
            val dir = java.io.File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "StreamFlix").apply { mkdirs() }
            java.io.File(dir, "crash.log").appendText("${java.util.Date()} [${t.name}]\n${e.stackTraceToString()}\n\n")
        }
        e.printStackTrace()
    }
    Automation.startIfEnabled()
    Runtime.getRuntime().addShutdownHook(Thread { Library.flush() })

    application {
        // True full screen: no title bar, and the taskbar is hidden (Alt+F4 or Settings to quit).
        val windowState = rememberWindowState(placement = WindowPlacement.Fullscreen, size = DpSize(1440.dp, 900.dp))
        val icon = remember { BitmapPainter(useResource("streamflix.png", ::loadImageBitmap)) }
        var themeMode by remember { mutableStateOf(UserPreferences.themeMode) }

        Window(
            onCloseRequest = {
                Library.flush()
                exitApplication()
            },
            state = windowState,
            undecorated = true,
            title = "StreamFlix",
            icon = icon,
            onPreviewKeyEvent = { e ->
                e.type == KeyEventType.KeyDown && Nav.playing == null && ((e.isAltPressed && e.key == Key.DirectionLeft) || e.key == Key.Escape) && Nav.back()
            },
        ) {
            window.minimumSize = java.awt.Dimension(1000, 640)
            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context)
                    .components { add(OkHttpNetworkFetcherFactory(callFactory = { NetworkClient.default })) }
                    .crossfade(true)
                    .build()
            }
            StreamFlixTheme(themeMode) {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                    val playing = Nav.playing
                    if (playing != null) {
                        PlayerScreen(playing)
                    } else {
                        AppShell(onThemeChanged = { themeMode = UserPreferences.themeMode })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AppShell(onThemeChanged: () -> Unit) {
    Row(
        Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press) { e ->
            // The mouse's back button.
            if (e.button == PointerButton.Back) Nav.back()
        },
    ) {
        NavigationRail(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxHeight(),
            header = { Spacer(Modifier.height(12.dp)) },
        ) {
            tabSpecs.forEach { spec ->
                val selected = Nav.tab == spec.screen
                NavigationRailItem(
                    selected = selected,
                    onClick = { Nav.selectTab(spec.screen) },
                    icon = { Icon(if (selected) spec.selectedIcon else spec.icon, spec.label) },
                    label = { Text(spec.label) },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            AnimatedContent(
                targetState = Nav.current,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "screen",
            ) { screen ->
                when (screen) {
                    Screen.Home -> HomeScreen()
                    Screen.Search -> SearchScreen()
                    Screen.Movies -> MoviesScreen()
                    Screen.Shows -> ShowsScreen()
                    Screen.Queue -> QueueScreen()
                    Screen.Settings -> SettingsScreen(onThemeChanged)
                    is Screen.MovieDetails -> MovieDetailsScreen(screen.id)
                    is Screen.ShowDetails -> ShowDetailsScreen(screen.id)
                    is Screen.Genre -> GenreScreen(screen.id, screen.name)
                    is Screen.Person -> PersonScreen(screen.id, screen.name)
                }
            }
        }
    }
}
