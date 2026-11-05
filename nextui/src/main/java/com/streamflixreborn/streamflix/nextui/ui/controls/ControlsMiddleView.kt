package com.streamflixreborn.streamflix.nextui.ui.controls

import androidx.annotation.OptIn
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.streamflixreborn.streamflix.nextui.buttons.NextButton
import com.streamflixreborn.streamflix.nextui.buttons.PlayPauseButton
import com.streamflixreborn.streamflix.nextui.buttons.PreviousButton
import com.streamflixreborn.streamflix.nextui.components.PlayerLoadingIndicator

/**
 * Previous / play-pause / next. Previous and next are the neighbouring episodes (hidden for movies).
 * While a server is being looked up or the stream is loading, play/pause becomes a loading indicator.
 */
@OptIn(UnstableApi::class)
@Composable
fun ControlsMiddleView(
    player: Player?,
    hasPrevious: Boolean,
    hasNext: Boolean,
    isLoading: Boolean,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playPauseFocusRequester = remember { FocusRequester() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusProperties { onEnter = { playPauseFocusRequester.requestFocus() } }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(
            40.dp,
            alignment = Alignment.CenterHorizontally,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasPrevious) PreviousButton(onClick = onPreviousClick) else Box(Modifier.size(60.dp))
        if (isLoading) {
            // Keeps the focus target alive so D-pad focus doesn't jump while loading.
            PlayerLoadingIndicator(
                modifier = Modifier
                    .focusRequester(focusRequester = playPauseFocusRequester)
                    .focusable(),
            )
        } else {
            PlayPauseButton(
                player = player,
                modifier = Modifier.focusRequester(focusRequester = playPauseFocusRequester),
            )
        }
        if (hasNext) NextButton(onClick = onNextClick) else Box(Modifier.size(60.dp))
    }
}
