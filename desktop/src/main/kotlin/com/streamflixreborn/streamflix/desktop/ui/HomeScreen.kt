package com.streamflixreborn.streamflix.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.desktop.Nav
import com.streamflixreborn.streamflix.desktop.data.Library
import com.streamflixreborn.streamflix.desktop.data.LibraryItem
import com.streamflixreborn.streamflix.desktop.data.Repo
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import kotlinx.coroutines.delay
import java.util.Calendar

private sealed interface HomeState {
    data object Loading : HomeState
    data class Ready(val categories: List<Category>) : HomeState
    data class Failed(val message: String) : HomeState
}

private var lastHome: HomeState = HomeState.Loading

@Composable
fun HomeScreen() {
    var state by remember { mutableStateOf(lastHome) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        if (state is HomeState.Ready && reload == 0) return@LaunchedEffect
        state = HomeState.Loading
        state = runCatching { HomeState.Ready(Repo.home(force = reload > 0)) }
            .getOrElse { HomeState.Failed(it.message ?: it.toString()) }
        lastHome = state
    }
    val library by Library.items.collectAsState()

    when (val s = state) {
        HomeState.Loading -> CenteredLoading()
        is HomeState.Failed -> EmptyState("Couldn’t load home", s.message) {
            Button(onClick = { reload++ }) { Text("Try again") }
        }
        is HomeState.Ready -> {
            val featured = s.categories.firstOrNull { it.name == Category.FEATURED }?.list.orEmpty()
            val rows = s.categories.filter { it.name != Category.FEATURED }
            val continueWatching = library.values.filter { it.inProgress }.sortedByDescending { it.lastPlayedAt }
            val queue = library.values.filter { it.inQueue && !it.watched }.sortedByDescending { it.queuedAt }
            LazyColumn(Modifier.fillMaxSize()) {
                if (featured.isNotEmpty()) item("hero") { Hero(featured) }
                if (continueWatching.isNotEmpty()) item("continue") {
                    Shelf("Continue watching", continueWatching, key = { it.key }) { LibraryPoster(it, showProgress = true) }
                }
                if (queue.isNotEmpty()) item("queue") {
                    Shelf("From your Queue", queue, key = { it.key }) { LibraryPoster(it) }
                }
                items(rows, key = { "row:" + it.name }) { category ->
                    val posters = category.list.mapNotNull { it.posterInfo() }
                    if (posters.isNotEmpty()) {
                        Shelf(category.name, posters, key = { (if (it.isTv) "t" else "m") + it.id }) { PosterCard(it) }
                    }
                }
                item("end") { Spacer(Modifier.height(48.dp)) }
            }
        }
    }
}

@Composable
fun LibraryPoster(item: LibraryItem, showProgress: Boolean = false) {
    val info = PosterInfo(item.id, item.isTv, item.title, item.poster, item.year, item.rating)
    val episode = item.lastEpisode
    PosterCard(
        info,
        progress = if (!showProgress) null else if (item.isTv) episode?.let { if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else 0f } else item.progress,
        badge = if (showProgress && item.isTv && episode != null) "S${episode.season} · E${episode.number}" else null,
    )
}

/** The featured carousel: a wide backdrop with title, overview and Play / More info, auto-advancing. */
@Composable
private fun Hero(items: List<AppAdapter.Item>) {
    val featured = remember(items) { items.filter { it is Movie || it is TvShow }.take(8) }
    if (featured.isEmpty()) return
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(featured) {
        while (true) {
            delay(9_000)
            index = (index + 1) % featured.size
        }
    }
    Box(Modifier.fillMaxWidth().height(520.dp)) {
        AnimatedContent(featured[index], transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "hero") { item ->
            val title: String
            val banner: String?
            val overview: String?
            val year: String?
            val info = item.posterInfo()!!
            when (item) {
                is Movie -> { title = item.title; banner = item.banner ?: item.poster; overview = item.overview; year = item.released?.get(Calendar.YEAR)?.toString() }
                is TvShow -> { title = item.title; banner = item.banner ?: item.poster; overview = item.overview; year = item.released?.get(Calendar.YEAR)?.toString() }
                else -> return@AnimatedContent
            }
            Box(Modifier.fillMaxSize()) {
                Artwork(banner, title, Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.55f to Color.Black.copy(alpha = 0.25f), 1f to Color.Transparent),
                    ),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.6f to Color.Transparent, 1f to MaterialTheme.colorScheme.surface),
                    ),
                )
                Column(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 56.dp).widthIn(max = 640.dp)) {
                    Text(if (info.isTv) "SERIES" else "FILM", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(title, style = MaterialTheme.typography.displaySmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        year?.let { Text(it, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge) }
                        RatingChip(info.rating)
                    }
                    if (!overview.isNullOrBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(overview, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { openDetails(info) }, contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight), modifier = Modifier.height(ButtonDefaults.MediumContainerHeight)) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Watch now", style = MaterialTheme.typography.titleMedium)
                        }
                        FilledTonalButton(onClick = { openDetails(info) }, modifier = Modifier.height(ButtonDefaults.MediumContainerHeight)) {
                            Icon(Icons.Rounded.Info, null)
                            Spacer(Modifier.width(8.dp))
                            Text("More info", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
        // Page dots.
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            featured.indices.forEach { i ->
                Box(
                    Modifier
                        .size(width = if (i == index) 28.dp else 8.dp, height = 8.dp)
                        .clip(CircleShape)
                        .background(if (i == index) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f))
                        .clickable { index = i },
                )
            }
        }
    }
}
