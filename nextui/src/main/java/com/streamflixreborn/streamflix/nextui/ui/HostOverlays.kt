package com.streamflixreborn.streamflix.nextui.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.streamflixreborn.streamflix.nextui.LocalIsTv
import com.streamflixreborn.streamflix.nextui.NextUiModel
import com.streamflixreborn.streamflix.nextui.R
import com.streamflixreborn.streamflix.nextui.UpNext
import com.streamflixreborn.streamflix.nextui.buttons.PlayerButton
import com.streamflixreborn.streamflix.nextui.components.tvFocusRing
import kotlinx.coroutines.delay

/** "Skip intro / recap / credits" pill. */
@Composable
internal fun BoxScope.SkipSegmentPill(
    label: String?,
    bottomPadding: Dp,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    var lastLabel by remember { mutableStateOf("") }
    if (label != null) lastLabel = label
    AnimatedVisibility(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(end = 16.dp, bottom = bottomPadding),
        visible = label != null,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        PlayerButton(
            modifier = Modifier.focusRequester(focusRequester),
            onClick = onClick,
            containerColor = Color.Black.copy(alpha = 0.65f),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = lastLabel, style = MaterialTheme.typography.labelLarge)
                Icon(
                    painter = painterResource(R.drawable.ic_skip_next),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Short transient message ("Switched to ...") at the top of the screen. */
@Composable
internal fun BoxScope.PlayerMessage(model: NextUiModel) {
    val message = model.message
    LaunchedEffect(message?.id) {
        if (message != null) {
            delay(3_000)
            model.clearMessage()
        }
    }
    var lastText by remember { mutableStateOf("") }
    if (message != null) lastText = message.text
    AnimatedVisibility(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = 16.dp, start = 16.dp, end = 16.dp),
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text(
                text = lastText,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Up next" card with the autoplay countdown. */
@Composable
internal fun BoxScope.UpNextCard(
    upNext: UpNext?,
    bottomPadding: Dp,
    focusRequester: FocusRequester,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
) {
    var last by remember { mutableStateOf<UpNext?>(null) }
    if (upNext != null) last = upNext
    val card = last ?: return
    val isTv = LocalIsTv.current
    AnimatedVisibility(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(end = 16.dp, bottom = bottomPadding),
        visible = upNext != null,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f))
                .padding(16.dp)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val poster = card.poster
                Box(
                    modifier = Modifier
                        .size(width = 100.dp, height = 56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    if (poster != null) {
                        Image(
                            bitmap = poster,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.label.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = card.meta,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val secondsLeft = card.secondsLeft
                if (secondsLeft != null) {
                    Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = {
                                secondsLeft.coerceIn(0, card.totalSeconds).toFloat() /
                                    card.totalSeconds.coerceAtLeast(1)
                            },
                            modifier = Modifier.size(44.dp),
                            strokeWidth = 3.dp,
                        )
                        Text(
                            text = secondsLeft.toString(),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Text(
                text = card.countdownText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    modifier = Modifier.tvFocusRing(isTv = isTv, shape = RoundedCornerShape(50)),
                    onClick = onCancel,
                ) { Text(card.cancelLabel) }
                Button(
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .tvFocusRing(isTv = isTv, shape = RoundedCornerShape(50)),
                    onClick = onPlay,
                ) { Text(card.playLabel) }
            }
            Spacer(Modifier.height(0.dp))
        }
    }
}
