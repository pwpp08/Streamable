package com.streamflixreborn.streamflix.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.desktop.Nav
import com.streamflixreborn.streamflix.desktop.Screen
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import kotlinx.coroutines.launch
import java.util.Calendar

/** Artwork with the title's initial behind it while it loads (or if there is none). */
@Composable
fun Artwork(url: String?, title: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Text(
            title.trim().take(1).uppercase(),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Hover lift shared by every card. */
@Composable
fun Modifier.hoverLift(interaction: MutableInteractionSource): Modifier {
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered) 1.05f else 1f)
    return this.hoverable(interaction).graphicsLayer { scaleX = scale; scaleY = scale }
}

data class PosterInfo(
    val id: String,
    val isTv: Boolean,
    val title: String,
    val poster: String?,
    val year: String?,
    val rating: Double?,
)

fun AppAdapter.Item.posterInfo(): PosterInfo? = when (this) {
    is Movie -> PosterInfo(id, false, title, poster, released?.get(Calendar.YEAR)?.toString(), rating)
    is TvShow -> PosterInfo(id, true, title, poster, released?.get(Calendar.YEAR)?.toString(), rating)
    else -> null
}

fun openDetails(info: PosterInfo) = Nav.open(if (info.isTv) Screen.ShowDetails(info.id) else Screen.MovieDetails(info.id))

/** A 2:3 poster card with title and year below, as on the phone's home rows. */
@Composable
fun PosterCard(
    info: PosterInfo,
    width: Dp = 150.dp,
    progress: Float? = null,
    badge: String? = null,
    onClick: () -> Unit = { openDetails(info) },
) {
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.width(width)) {
        Box(
            Modifier
                .hoverLift(interaction)
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(MaterialTheme.shapes.medium)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        ) {
            Artwork(info.poster, info.title, Modifier.fillMaxSize())
            if (badge != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(bottomEnd = 12.dp),
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Text(badge, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            if (progress != null && progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(info.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(info.year, info.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** A horizontally scrolling shelf with arrow buttons on hover (mouse wheels scroll vertically). */
@Composable
fun <T> Shelf(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    state: LazyListState = rememberLazyListState(),
    itemWidth: Dp = 150.dp,
    content: @Composable (T) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        SectionTitle(title)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().hoverable(interaction)) {
            LazyRow(
                state = state,
                contentPadding = PaddingValues(horizontal = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(items, key = key) { content(it) }
            }
            val step = with(androidx.compose.ui.platform.LocalDensity.current) { (itemWidth + 16.dp).toPx() * 4 }
            if (hovered && state.canScrollBackward) {
                ShelfArrow(Modifier.align(Alignment.CenterStart).padding(start = 8.dp), left = true) {
                    scope.launch { state.animateScrollBy(-step) }
                }
            }
            if (hovered && state.canScrollForward) {
                ShelfArrow(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), left = false) {
                    scope.launch { state.animateScrollBy(step) }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.ShelfArrow(modifier: Modifier, left: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Icon(
            if (left) Icons.AutoMirrored.Rounded.KeyboardArrowLeft else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
        )
    }
}

@Composable
fun CenteredLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(64.dp)) }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** Bottom-to-top scrim so text stays readable over artwork. */
val scrimBrush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))

@Composable
fun RatingChip(rating: Double?) {
    if (rating == null || rating <= 0) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Star, null, tint = Color(0xFFFFC94D), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("%.1f".format(rating), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}
