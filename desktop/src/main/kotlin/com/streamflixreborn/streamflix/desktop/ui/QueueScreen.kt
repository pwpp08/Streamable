package com.streamflixreborn.streamflix.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamflixreborn.streamflix.desktop.Nav
import com.streamflixreborn.streamflix.desktop.Screen
import com.streamflixreborn.streamflix.desktop.data.Library
import com.streamflixreborn.streamflix.desktop.data.LibraryItem
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private enum class QueueSection(val label: String) { IN_PROGRESS("In progress"), WATCHLIST("Watchlist"), WATCHED("Watched") }

/**
 * Connected button group with square inner corners (only the outer ends are round), like the
 * phone app's toggle groups.
 */
@Composable
fun <T> ConnectedToggle(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        val full = CornerSize(50)
        val inner = CornerSize(8.dp)
        options.forEachIndexed { i, option ->
            val shape = when {
                options.size == 1 -> RoundedCornerShape(full)
                i == 0 -> RoundedCornerShape(topStart = full, topEnd = inner, bottomEnd = inner, bottomStart = full)
                i == options.lastIndex -> RoundedCornerShape(topStart = inner, topEnd = full, bottomEnd = full, bottomStart = inner)
                else -> RoundedCornerShape(inner)
            }
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                shapes = ToggleButtonShapes(shape, shape, shape),
                modifier = Modifier.semantics { role = Role.RadioButton },
            ) { Text(label(option)) }
        }
    }
}

@Composable
fun QueueScreen() {
    val library by Library.items.collectAsState()
    var section by remember { mutableStateOf(QueueSection.IN_PROGRESS) }
    var spinning by remember { mutableStateOf(false) }

    val inProgress = library.values.filter { it.inProgress }.sortedByDescending { it.lastPlayedAt }
    val watchlist = library.values.filter { it.inQueue && !it.watched && !it.inProgress }.sortedByDescending { it.queuedAt }
    val watched = library.values.filter { it.watched }.sortedByDescending { it.watchedAt }
    val list = when (section) {
        QueueSection.IN_PROGRESS -> inProgress
        QueueSection.WATCHLIST -> watchlist
        QueueSection.WATCHED -> watched
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(170.dp),
        contentPadding = PaddingValues(start = 32.dp, end = 32.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 32.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Queue", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            "${inProgress.size} in progress · ${watchlist.size} to watch · ${watched.size} watched",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = { spinning = true },
                        enabled = (inProgress + watchlist).isNotEmpty(),
                        modifier = Modifier.height(ButtonDefaults.MediumContainerHeight),
                    ) {
                        Icon(Icons.Rounded.Casino, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Spin", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.height(20.dp))
                ConnectedToggle(QueueSection.entries, section, { s ->
                    val n = when (s) { QueueSection.IN_PROGRESS -> inProgress.size; QueueSection.WATCHLIST -> watchlist.size; QueueSection.WATCHED -> watched.size }
                    "${s.label} · $n"
                }) { section = it }
            }
        }
        if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.height(360.dp)) {
                when (section) {
                    QueueSection.IN_PROGRESS -> EmptyState("Nothing in progress", "Start a film or a show and it waits for you here.")
                    QueueSection.WATCHLIST -> EmptyState("Your Queue is empty", "Use “Add to Queue” on any title to save it for later.")
                    QueueSection.WATCHED -> EmptyState("Nothing watched yet", "Finished titles land here.")
                }
            }
        }
        items(list, key = { it.key }) { LibraryPoster(it, showProgress = section == QueueSection.IN_PROGRESS) }
    }

    if (spinning) SpinDialog((inProgress + watchlist).distinctBy { it.key }) { spinning = false }
}

/**
 * The decision spinner: the current pick big on top, and a fan of 2:3 posters at the bottom.
 * Drag the fan or press Spin; it slows down and lands on a random title.
 */
@Composable
private fun SpinDialog(pool: List<LibraryItem>, onDismiss: () -> Unit) {
    var filter by remember { mutableStateOf("All") }
    val titles = remember(pool, filter) {
        pool.filter { filter == "All" || (filter == "Shows") == it.isTv }.shuffled().take(16)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            if (titles.isEmpty()) {
                EmptyState("Nothing to spin", "No ${filter.lowercase()} in your Queue.")
            } else {
                SpinContent(titles, onDismiss)
            }
            Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                ConnectedToggle(listOf("All", "Movies", "Shows"), filter, { it }) { filter = it }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
            }
        }
    }
}

@Composable
private fun SpinContent(titles: List<LibraryItem>, onDismiss: () -> Unit) {
    val slots = titles.size * kotlin.math.ceil(11f / titles.size).toInt()
    val offset = remember(titles) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val front = Math.floorMod(offset.value.roundToInt(), slots) % titles.size
    val pick = titles[front]

    fun spin(direction: Int = 1) {
        scope.launch {
            val distance = slots * (2 + Random.nextInt(2)) + Random.nextInt(slots)
            offset.animateTo(
                (offset.value.roundToInt() + direction * distance).toFloat(),
                tween(4200, easing = CubicBezierEasing(0.12f, 0.8f, 0.2f, 1f)),
            )
        }
    }
    LaunchedEffect(titles) { spin() }

    Column(Modifier.fillMaxSize().padding(top = 88.dp)) {
        // The pick.
        Row(Modifier.fillMaxWidth().weight(0.55f).padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(pick.banner ?: pick.poster, pick.title, Modifier.weight(1.2f).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.extraLarge))
            Spacer(Modifier.width(40.dp))
            Column(Modifier.weight(1f).widthIn(max = 560.dp)) {
                Text(if (pick.isTv) "SERIES" else "FILM", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(pick.title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(pick.year, pick.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                pick.overview?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        onDismiss()
                        Nav.selectTab(Screen.Queue)
                        Nav.open(if (pick.isTv) Screen.ShowDetails(pick.id) else Screen.MovieDetails(pick.id))
                    }, modifier = Modifier.height(ButtonDefaults.MediumContainerHeight)) {
                        Icon(Icons.Rounded.Info, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open", style = MaterialTheme.typography.titleMedium)
                    }
                    FilledTonalButton(onClick = { spin() }, enabled = !offset.isRunning, modifier = Modifier.height(ButtonDefaults.MediumContainerHeight)) {
                        Icon(Icons.Rounded.Casino, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Spin again", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        // The fan.
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(0.45f).pointerInput(titles) {
                var velocity = 0f
                detectHorizontalDragGestures(
                    onDragStart = { scope.launch { offset.stop() } },
                    onDragEnd = {
                        if (abs(velocity) > 18f) spin(if (velocity > 0) -1 else 1)
                        else scope.launch { offset.animateTo(offset.value.roundToInt().toFloat(), tween(240)) }
                        velocity = 0f
                    },
                ) { change, dx ->
                    change.consume()
                    velocity = dx
                    scope.launch { offset.snapTo(offset.value - dx / (size.width * 0.12f)) }
                }
            },
        ) {
            val cardW = minOf(maxWidth * 0.16f, maxHeight * 0.78f / 1.5f)
            val cardH = cardW * 1.5f
            val step = cardW * 0.56f
            val stepAngle = 11f
            val radius = step / sin(Math.toRadians(stepAngle.toDouble())).toFloat()
            fun dist(slot: Int): Float {
                var d = (slot - offset.value) % slots
                if (d > slots / 2f) d -= slots
                if (d < -slots / 2f) d += slots
                return d
            }
            val visible = (0 until slots).map { it to dist(it) }.filter { abs(it.second) <= 4.2f }.sortedByDescending { abs(it.second) }
            for ((slot, d) in visible) {
                val item = titles[slot % titles.size]
                val angle = d * stepAngle
                val a = Math.toRadians(angle.toDouble())
                val x = radius * sin(a).toFloat()
                val y = radius * (1 - cos(a).toFloat())
                val scale = (1f - 0.11f * abs(d)).coerceAtLeast(0.55f)
                val dim = (abs(d) * 0.22f).coerceAtMost(0.7f)
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .graphicsLayer {
                            translationX = x.toPx()
                            translationY = y.toPx()
                            rotationZ = angle
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                        }
                        .size(cardW, cardH)
                        .shadow(12.dp, MaterialTheme.shapes.large)
                        .clip(MaterialTheme.shapes.large),
                ) {
                    Artwork(item.poster, item.title, Modifier.fillMaxSize())
                    if (dim > 0.01f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(40.dp).background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface))))
        }
    }
}
