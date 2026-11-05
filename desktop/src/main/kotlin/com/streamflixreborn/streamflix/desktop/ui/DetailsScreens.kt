package com.streamflixreborn.streamflix.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.streamflixreborn.streamflix.desktop.Nav
import com.streamflixreborn.streamflix.desktop.PlayRequest
import com.streamflixreborn.streamflix.desktop.Screen
import com.streamflixreborn.streamflix.desktop.data.EpisodeState
import com.streamflixreborn.streamflix.desktop.data.Library
import com.streamflixreborn.streamflix.desktop.data.LibraryItem
import com.streamflixreborn.streamflix.desktop.data.Repo
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import java.util.Calendar

private fun formatRuntime(minutes: Int?) = minutes?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** The top-left back button every details page has. */
@Composable
fun BackButton(modifier: Modifier = Modifier) {
    FilledTonalIconButton(
        onClick = { Nav.back() },
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)),
        modifier = modifier.padding(16.dp).size(48.dp),
    ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
}

@Composable
private fun <T> Loaded(key: Any, load: suspend () -> T, content: @Composable (T) -> Unit) {
    var value by remember(key) { mutableStateOf<Result<T>?>(null) }
    LaunchedEffect(key) { value = runCatching { load() } }
    Box(Modifier.fillMaxSize()) {
        when (val v = value) {
            null -> CenteredLoading()
            else -> v.fold(
                onSuccess = { content(it) },
                onFailure = { EmptyState("Couldn’t load this title", it.message ?: it.toString()) },
            )
        }
        BackButton(Modifier.align(Alignment.TopStart))
    }
}

/** Backdrop, poster and the text block shared by movie and show pages. */
@Composable
private fun DetailsHeader(
    title: String,
    banner: String?,
    poster: String?,
    meta: List<String>,
    rating: Double?,
    genres: List<Genre>,
    overview: String?,
    actions: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(560.dp)) {
        Artwork(banner ?: poster, title, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.9f), 0.6f to Color.Black.copy(alpha = 0.35f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to MaterialTheme.colorScheme.surface)))
        Row(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, end = 48.dp, bottom = 32.dp), verticalAlignment = Alignment.Bottom) {
            Artwork(poster, title, Modifier.width(220.dp).aspectRatio(2f / 3f).clip(MaterialTheme.shapes.large))
            Spacer(Modifier.width(32.dp))
            Column(Modifier.widthIn(max = 760.dp)) {
                Text(title, style = MaterialTheme.typography.displaySmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    meta.forEach { Text(it, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f)) }
                    RatingChip(rating)
                }
                if (genres.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        genres.take(5).forEach { g -> Pill(g.name, Modifier.clip(CircleShape).clickable { Nav.open(Screen.Genre(g.id, g.name)) }) }
                    }
                }
                if (!overview.isNullOrBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text(overview, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.88f), maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(22.dp))
                actions()
            }
        }
    }
}

@Composable
private fun PrimaryPlayButton(label: String, sub: String? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(ButtonDefaults.MediumContainerHeight),
        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
    ) {
        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(ButtonDefaults.MediumIconSize))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
        if (sub != null) {
            Spacer(Modifier.width(8.dp))
            Text(sub, style = MaterialTheme.typography.titleMedium, color = LocalContentColor.current.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun QueueButton(item: LibraryItem) {
    FilledTonalButton(onClick = { Library.setInQueue(item, !item.inQueue) }, modifier = Modifier.height(ButtonDefaults.MediumContainerHeight)) {
        Icon(if (item.inQueue) Icons.Rounded.Check else Icons.Rounded.Add, null)
        Spacer(Modifier.width(8.dp))
        Text(if (item.inQueue) "In Queue" else "Add to Queue", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun WatchedToggle(watched: Boolean, onToggle: (Boolean) -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(if (watched) "Mark as unwatched" else "Mark as watched") } },
        state = rememberTooltipState(),
    ) {
        FilledIconToggleButton(checked = watched, onCheckedChange = onToggle, modifier = Modifier.size(ButtonDefaults.MediumContainerHeight)) {
            Icon(if (watched) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, null)
        }
    }
}

@Composable
private fun CastShelf(cast: List<People>) {
    if (cast.isEmpty()) return
    Column(Modifier.padding(vertical = 12.dp)) {
        SectionTitle("Cast")
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            items(cast.take(24), key = { it.id }) { person ->
                val interaction = remember { MutableInteractionSource() }
                Column(
                    Modifier.width(110.dp).hoverLift(interaction).clickable(interaction, null) { Nav.open(Screen.Person(person.id, person.name)) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Artwork(person.image, person.name, Modifier.size(96.dp).clip(CircleShape))
                    Spacer(Modifier.height(8.dp))
                    Text(person.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Recommendations(shows: List<Show>) {
    val posters = shows.mapNotNull { (it as? com.streamflixreborn.streamflix.adapters.AppAdapter.Item)?.posterInfo() }
    if (posters.isEmpty()) return
    Shelf("More like this", posters, key = { (if (it.isTv) "t" else "m") + it.id }) { PosterCard(it) }
}

// ---- Movie ---------------------------------------------------------------------------------

@Composable
fun MovieDetailsScreen(id: String) = Loaded("movie:$id", { Repo.movie(id) }) { movie -> MovieDetails(movie) }

@Composable
private fun MovieDetails(movie: Movie) {
    val library by Library.items.collectAsState()
    val item = library[Library.key(false, movie.id)] ?: Library.itemFor(movie)
    val year = movie.released?.get(Calendar.YEAR)?.toString()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            DetailsHeader(
                title = movie.title,
                banner = movie.banner,
                poster = movie.poster,
                meta = listOfNotNull("Film", year, formatRuntime(movie.runtime)),
                rating = movie.rating,
                genres = movie.genres,
                overview = movie.overview,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val resume = item.positionMs > 60_000 && !item.watched
                    PrimaryPlayButton(if (resume) "Resume" else "Play", if (resume) "from ${formatTime(item.positionMs)}" else null) {
                        Library.edit(Library.itemFor(movie)) { it }
                        Nav.play(PlayRequest(Repo.movieType(movie), movie.title, year, startMs = if (resume) item.positionMs else 0))
                    }
                    QueueButton(item)
                    WatchedToggle(item.watched) { Library.setWatched(Library.itemFor(movie), it) }
                }
                if (item.positionMs > 0 && !item.watched) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.width(360.dp))
                }
            }
        }
        item { CastShelf(movie.cast) }
        item { Recommendations(movie.recommendations) }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

// ---- TV show -------------------------------------------------------------------------------

@Composable
fun ShowDetailsScreen(id: String) = Loaded("show:$id", { Repo.show(id) }) { show -> ShowDetails(show) }

@Composable
private fun ShowDetails(show: TvShow) {
    val library by Library.items.collectAsState()
    val item = library[Library.key(true, show.id)] ?: Library.itemFor(show)
    val seasons = remember(show) { show.seasons.sortedWith(compareBy({ it.number == 0 }, { it.number })) }
    val last = item.lastEpisode
    var selected by remember(show.id) {
        mutableStateOf(seasons.firstOrNull { it.number == last?.season } ?: seasons.firstOrNull())
    }
    var episodes by remember(show.id, selected?.id) { mutableStateOf<List<Episode>?>(null) }
    LaunchedEffect(show.id, selected?.id) {
        val s = selected ?: return@LaunchedEffect
        episodes = runCatching { Repo.episodes(s.id) }.getOrDefault(emptyList())
    }

    fun play(seasonNumber: Int, seasonTitle: String?, episode: Episode, startMs: Long) {
        Library.edit(Library.itemFor(show)) { it }
        Nav.play(
            PlayRequest(
                Repo.episodeType(show, seasonNumber, seasonTitle, episode),
                show.title,
                "S$seasonNumber · E${episode.number}" + (episode.title?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                startMs,
            ),
        )
    }

    // What Play starts: the last episode if unfinished, else the next one, else the first unwatched.
    val target: Triple<Int, Episode, Long>? = run {
        val seasonNumber = selected?.number ?: return@run null
        val list = episodes ?: return@run null
        if (last != null && last.season == seasonNumber) {
            val ep = list.firstOrNull { it.number == last.number }
            if (ep != null && !last.watched && last.positionMs > 0) return@run Triple(seasonNumber, ep, last.positionMs)
            list.firstOrNull { it.number > last.number }?.let { return@run Triple(seasonNumber, it, 0L) }
        }
        val unwatched = list.firstOrNull { library[item.key]?.episodes?.get("$seasonNumber:${it.number}")?.watched != true }
        (unwatched ?: list.firstOrNull())?.let { Triple(seasonNumber, it, 0L) }
    }

    val year = show.released?.get(Calendar.YEAR)?.toString()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            DetailsHeader(
                title = show.title,
                banner = show.banner,
                poster = show.poster,
                meta = listOfNotNull("Series", year, seasons.count { it.number > 0 }.takeIf { it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" }),
                rating = show.rating,
                genres = show.genres,
                overview = show.overview,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (target != null) {
                        val (sn, ep, start) = target
                        PrimaryPlayButton(if (start > 0) "Resume" else "Play", "S$sn · E${ep.number}") { play(sn, selected?.title, ep, start) }
                    } else {
                        PrimaryPlayButton("Play") {}
                    }
                    QueueButton(item)
                    WatchedToggle(item.watched) { Library.setWatched(Library.itemFor(show), it) }
                }
            }
        }
        if (seasons.isNotEmpty()) item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasons, key = { it.id }) { s ->
                        FilterChip(
                            selected = s.id == selected?.id,
                            onClick = { selected = s },
                            label = { Text(s.title?.takeIf { it.isNotBlank() } ?: if (s.number == 0) "Specials" else "Season ${s.number}") },
                        )
                    }
                }
            }
        }
        val list = episodes
        if (list == null && selected != null) item {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
        }
        if (list != null) items(list, key = { "ep:" + it.id }) { ep ->
            val sn = selected?.number ?: 0
            val state = library[item.key]?.episodes?.get("$sn:${ep.number}")
            EpisodeRow(
                episode = ep,
                state = state,
                onPlay = { play(sn, selected?.title, ep, if (state != null && !state.watched) state.positionMs else 0) },
                onToggleWatched = {
                    Library.setEpisodeWatched(
                        Library.itemFor(show),
                        EpisodeState(ep.id, sn, ep.number, ep.title),
                        state?.watched != true,
                    )
                },
            )
        }
        item { CastShelf(show.cast) }
        item { Recommendations(show.recommendations) }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

@Composable
private fun EpisodeRow(episode: Episode, state: EpisodeState?, onPlay: () -> Unit, onToggleWatched: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onPlay,
        interactionSource = interaction,
        color = Color.Transparent,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).widthIn(max = 1100.dp).fillMaxWidth(),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(240.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)) {
                Artwork(episode.poster, "${episode.number}", Modifier.fillMaxSize())
                Icon(
                    Icons.Rounded.PlayArrow, null, tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(44.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(8.dp),
                )
                if (state != null && state.durationMs > 0 && !state.watched && state.positionMs > 0) {
                    LinearProgressIndicator(
                        progress = { state.positionMs.toFloat() / state.durationMs },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${episode.number}. " + (episode.title?.takeIf { it.isNotBlank() } ?: "Episode ${episode.number}"),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                episode.released?.let {
                    Text(
                        "%d-%02d-%02d".format(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!episode.overview.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(episode.overview!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(16.dp))
            FilledIconToggleButton(checked = state?.watched == true, onCheckedChange = { onToggleWatched() }, modifier = Modifier.size(48.dp)) {
                Icon(if (state?.watched == true) Icons.Rounded.CheckCircle else Icons.Rounded.Check, if (state?.watched == true) "Watched" else "Mark watched")
            }
        }
    }
}
