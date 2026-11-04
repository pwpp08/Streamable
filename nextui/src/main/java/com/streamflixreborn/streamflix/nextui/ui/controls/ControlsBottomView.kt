package com.streamflixreborn.streamflix.nextui.ui.controls

import androidx.annotation.OptIn
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util.getStringForTime
import androidx.media3.ui.compose.state.ProgressStateWithTickInterval
import com.streamflixreborn.streamflix.nextui.LocalIsTv
import com.streamflixreborn.streamflix.nextui.R
import com.streamflixreborn.streamflix.nextui.buttons.PlaybackSpeedButton
import com.streamflixreborn.streamflix.nextui.buttons.PlayerButton
import com.streamflixreborn.streamflix.nextui.buttons.PlayerButtonBlackAlpha
import com.streamflixreborn.streamflix.nextui.buttons.RotateButton
import com.streamflixreborn.streamflix.nextui.common.copy
import com.streamflixreborn.streamflix.nextui.extensions.drawableRes
import com.streamflixreborn.streamflix.nextui.model.VideoContentScale

private const val MILLISECONDS_PER_SECOND = 1_000L

@OptIn(UnstableApi::class)
@Composable
fun ControlsBottomView(
    player: Player?,
    progressState: ProgressStateWithTickInterval,
    videoContentScale: VideoContentScale,
    isPipSupported: Boolean,
    onVideoContentScaleClick: () -> Unit,
    onLockControlsClick: () -> Unit,
    onPictureInPictureClick: () -> Unit,
    onPlaybackSpeedClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val systemBarsPadding = WindowInsets.systemBars.union(
        WindowInsets.displayCutout,
    ).asPaddingValues()
    val isTv = LocalIsTv.current
    val timeButtonFocusRequester = remember { FocusRequester() }
    var showRemainingTime by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .padding(systemBarsPadding.copy(top = 0.dp))
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp)
            .padding(
                bottom =
                    16.dp.takeIf { systemBarsPadding.calculateBottomPadding() == 0.dp } ?: 0.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .focusProperties { onEnter = { timeButtonFocusRequester.requestFocus() } }
                .focusGroup(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerButton(
                modifier = Modifier.focusRequester(focusRequester = timeButtonFocusRequester),
                onClick = { showRemainingTime = !showRemainingTime },
                containerColor = PlayerButtonBlackAlpha,
                contentPadding = PaddingValues(
                    horizontal = 14.dp,
                    vertical = 6.dp,
                ),
            ) {
                val timeTextPositionMs by remember(progressState) {
                    derivedStateOf {
                        val wholeSeconds = progressState.currentPositionMs / MILLISECONDS_PER_SECOND
                        wholeSeconds * MILLISECONDS_PER_SECOND
                    }
                }
                Text(
                    text = buildString {
                        val positionText = when (showRemainingTime) {
                            true -> if (progressState.durationMs != C.TIME_UNSET) {
                                val remainingMs = timeTextPositionMs - progressState.durationMs
                                getStringForTime(remainingMs)
                            } else {
                                getStringForTime(C.TIME_UNSET)
                            }

                            false -> getStringForTime(timeTextPositionMs)
                        }
                        append(positionText)
                        append(" / ")
                        append(getStringForTime(progressState.durationMs))
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Spacer(modifier = Modifier.weight(weight = 1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlaybackSpeedButton(
                    player = player,
                    onClick = onPlaybackSpeedClick,
                )

                if (!isTv) {
                    RotateButton()
                }
            }
        }
        PlayerSeekbar(
            position = progressState.currentPositionMs.toFloat(),
            duration = progressState.durationMs.toFloat(),
            onSeek = { onSeek(it.toLong()) },
            onSeekFinished = { onSeekEnd() },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(state = rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerButton(onClick = onLockControlsClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_lock_open),
                    contentDescription = null,
                )
            }
            PlayerButton(onClick = onVideoContentScaleClick) {
                Icon(
                    painter = painterResource(videoContentScale.drawableRes()),
                    contentDescription = null,
                )
            }
            if (isPipSupported) {
                PlayerButton(onClick = onPictureInPictureClick) {
                    Icon(
                        painter = painterResource(R.drawable.ic_pip),
                        contentDescription = null,
                    )
                }
            }
        }
    }
}
