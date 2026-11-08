package com.streamflixreborn.streamflix.nextui

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import com.streamflixreborn.streamflix.nextui.components.PlayerLoadingIndicator
import com.streamflixreborn.streamflix.nextui.components.requestFocusUntilLanded
import com.streamflixreborn.streamflix.nextui.components.thenIf
import com.streamflixreborn.streamflix.nextui.extensions.nameRes
import com.streamflixreborn.streamflix.nextui.model.DoubleTapGesture
import com.streamflixreborn.streamflix.nextui.state.rememberBrightnessState
import com.streamflixreborn.streamflix.nextui.state.rememberControlsVisibilityState
import com.streamflixreborn.streamflix.nextui.state.rememberMediaPresentationState
import com.streamflixreborn.streamflix.nextui.state.rememberPlayerKeyInputState
import com.streamflixreborn.streamflix.nextui.state.rememberSeekGestureState
import com.streamflixreborn.streamflix.nextui.state.rememberTapGestureState
import com.streamflixreborn.streamflix.nextui.state.rememberVideoZoomAndContentScaleState
import com.streamflixreborn.streamflix.nextui.state.rememberVolumeAndBrightnessGestureState
import com.streamflixreborn.streamflix.nextui.state.rememberVolumeState
import com.streamflixreborn.streamflix.nextui.state.seekAmountFormatted
import com.streamflixreborn.streamflix.nextui.state.seekToPositionFormated
import com.streamflixreborn.streamflix.nextui.ui.DpadSeekIndicator
import com.streamflixreborn.streamflix.nextui.ui.InfoView
import com.streamflixreborn.streamflix.nextui.ui.OverlayShowView
import com.streamflixreborn.streamflix.nextui.ui.OverlayView
import com.streamflixreborn.streamflix.nextui.ui.PlayerGestureIndicators
import com.streamflixreborn.streamflix.nextui.ui.PlayerGestures
import com.streamflixreborn.streamflix.nextui.ui.PlayerMessage
import com.streamflixreborn.streamflix.nextui.ui.PlayerVerticalGestureIndicators
import com.streamflixreborn.streamflix.nextui.ui.SkipSegmentPill
import com.streamflixreborn.streamflix.nextui.ui.UpNextCard
import com.streamflixreborn.streamflix.nextui.ui.controls.ControlsBottomView
import com.streamflixreborn.streamflix.nextui.ui.controls.ControlsMiddleView
import com.streamflixreborn.streamflix.nextui.ui.controls.ControlsTopView
import kotlin.time.Duration.Companion.seconds

private val EDGE_SCRIM = Color.Black.copy(alpha = 0.7f)
private val EDGE_SCRIM_MID = Color.Black.copy(alpha = 0.55f)

/** NextPlayer's control surface, driven by a plain media3 [Player] and a small host-owned model. */
@Composable
fun NextUi(player: Player?, model: NextUiModel, callbacks: NextUiCallbacks) {
    NextUiTheme {
        CompositionLocalProvider(LocalIsTv provides model.isTv) {
            if (player == null) {
                Box(Modifier.fillMaxSize())
            } else {
                // Every state object below is bound to one player; a new player starts them over.
                key(player) { NextUiContent(player = player, model = model, callbacks = callbacks) }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun NextUiContent(player: Player, model: NextUiModel, callbacks: NextUiCallbacks) {
    val isTv = model.isTv
    val seekIncrementMs = model.seekIncrementMs

    val controlsVisibilityState = rememberControlsVisibilityState(
        player = player,
        hideAfter = model.autoHideSeconds.seconds,
    )
    DisposableEffect(controlsVisibilityState) {
        model.controls = controlsVisibilityState
        onDispose { if (model.controls === controlsVisibilityState) model.controls = null }
    }
    val tapGestureState = rememberTapGestureState(
        player = player,
        doubleTapGesture = DoubleTapGesture.BOTH,
        seekIncrementMillis = seekIncrementMs,
        useLongPressGesture = false,
        longPressSpeed = 2f,
    )
    val seekGestureState = rememberSeekGestureState(
        player = player,
        sensitivity = 0.5f,
        enableSeekGesture = model.gesturesEnabled,
    )
    val volumeState = rememberVolumeState(showVolumePanelIfHeadsetIsOn = false)
    val brightnessState = rememberBrightnessState()
    val volumeAndBrightnessGestureState = brightnessState?.let {
        rememberVolumeAndBrightnessGestureState(
            volumeState = volumeState,
            brightnessState = it,
            enableVolumeGesture = model.gesturesEnabled,
            enableBrightnessGesture = model.gesturesEnabled,
            volumeGestureSensitivity = 0.5f,
            brightnessGestureSensitivity = 0.5f,
        )
    }
    val videoZoomAndContentScaleState = rememberVideoZoomAndContentScaleState(
        player = player,
        initialContentScale = model.initialContentScale,
        enableZoomGesture = model.gesturesEnabled,
        enablePanGesture = true,
        onContentScaleChanged = callbacks::onVideoContentScaleChanged,
    )
    LaunchedEffect(videoZoomAndContentScaleState) {
        snapshotFlow {
            val state = videoZoomAndContentScaleState
            Transform(state.videoContentScale, state.zoom, state.offset.x, state.offset.y)
        }.collect { callbacks.onVideoTransform(it.scale, it.zoom, it.offsetX, it.offsetY) }
    }

    val mediaPresentationState = rememberMediaPresentationState(player = player)
    val progressState = rememberProgressStateWithTickInterval(player = player)
    val keyInputState = rememberPlayerKeyInputState(
        player = player,
        controls = controlsVisibilityState,
        seekIncrementMs = seekIncrementMs,
        onMediaNext = { if (model.hasNext) callbacks.onNextEpisode() },
        onMediaPrevious = { if (model.hasPrevious) callbacks.onPreviousEpisode() },
    )

    val overlayView = when {
        model.picker != null -> OverlayView.PICKER
        model.speedSheetVisible -> OverlayView.PLAYBACK_SPEED
        else -> null
    }
    val dismissOverlay = {
        model.picker = null
        model.speedSheetVisible = false
    }
    LaunchedEffect(overlayView != null) {
        if (overlayView != null) controlsVisibilityState.hideControls()
    }
    LaunchedEffect(model.isInPictureInPictureMode) {
        if (model.isInPictureInPictureMode) controlsVisibilityState.hideControls()
    }

    val rootFocusRequester = remember { FocusRequester() }
    val middleControlsFocusRequester = remember { FocusRequester() }
    val unlockFocusRequester = remember { FocusRequester() }
    val skipFocusRequester = remember { FocusRequester() }
    val upNextFocusRequester = remember { FocusRequester() }
    var isMiddleControlsFocused by remember { mutableStateOf(false) }
    var isUnlockFocused by remember { mutableStateOf(false) }
    if (isTv) {
        LaunchedEffect(
            controlsVisibilityState.controlsVisible,
            controlsVisibilityState.controlsLocked,
            overlayView,
        ) {
            if (overlayView != null) return@LaunchedEffect
            if (!controlsVisibilityState.controlsVisible) {
                runCatching { rootFocusRequester.requestFocus() }
                return@LaunchedEffect
            }
            val locked = controlsVisibilityState.controlsLocked
            val target = if (locked) unlockFocusRequester else middleControlsFocusRequester
            target.requestFocusUntilLanded(attempts = 20) {
                if (locked) isUnlockFocused else isMiddleControlsFocused
            }
        }
    }

    val controlsShown = controlsVisibilityState.controlsVisible && !controlsVisibilityState.controlsLocked
    val isLoading = model.isLoading || mediaPresentationState.isBuffering
    val hostBottomPadding = if (controlsShown) 150.dp else 24.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .thenIf(condition = isTv) {
                focusRequester(focusRequester = rootFocusRequester)
                    .focusable()
                    .onPreviewKeyEvent { keyEvent ->
                        val hostTargetVisible = model.upNext != null || model.skipLabel != null
                        if (overlayView != null) {
                            false
                        } else if (hostTargetVisible &&
                            !controlsVisibilityState.controlsVisible &&
                            keyEvent.type == KeyEventType.KeyDown &&
                            (
                                keyEvent.key == Key.DirectionCenter ||
                                    keyEvent.key == Key.Enter ||
                                    keyEvent.key == Key.NumPadEnter ||
                                    keyEvent.key == Key.DirectionDown
                                )
                        ) {
                            // The Up-next card or skip pill takes D-pad focus when controls are hidden.
                            val target = if (model.upNext != null) upNextFocusRequester else skipFocusRequester
                            runCatching { target.requestFocus() }.isSuccess
                        } else {
                            keyInputState.onKeyEvent(keyEvent = keyEvent)
                        }
                    }
            },
    ) {
        PlayerGestures(
            controlsVisibilityState = controlsVisibilityState,
            tapGestureState = tapGestureState,
            isInPictureInPictureMode = model.isInPictureInPictureMode,
            seekGestureState = seekGestureState,
            videoZoomAndContentScaleState = videoZoomAndContentScaleState,
            volumeAndBrightnessGestureState = volumeAndBrightnessGestureState,
        )

        AnimatedVisibility(
            visible = controlsShown,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))) {
                // The title and the time sit on these: white text needs about 55 % black over a white frame.
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(160.dp)
                        .background(Brush.verticalGradient(0f to EDGE_SCRIM, 0.5f to EDGE_SCRIM_MID, 1f to Color.Transparent)),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to EDGE_SCRIM_MID, 1f to EDGE_SCRIM)),
                )
            }
        }
        // With controls up, the middle row shows the indicator in place of play/pause instead.
        if (isLoading && !controlsShown) {
            PlayerLoadingIndicator(Modifier.align(Alignment.Center))
        }
        PlayerGestureIndicators(tapGestureState = tapGestureState)
        DpadSeekIndicator(
            visible = keyInputState.isSeeking && keyInputState.seekOffsetMs != 0L,
            offsetMs = keyInputState.seekOffsetMs,
            positionMs = keyInputState.seekPositionMs,
        )

        if (controlsVisibilityState.controlsVisible && controlsVisibilityState.controlsLocked) {
            PlayerLockedControls(
                onUnlockControls = controlsVisibilityState::unlockControls,
                unlockButtonModifier = Modifier.thenIf(condition = isTv) {
                    focusRequester(focusRequester = unlockFocusRequester)
                        .onFocusChanged { isUnlockFocused = it.hasFocus }
                },
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column {
                    AnimatedVisibility(
                        visible = controlsVisibilityState.controlsVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        ControlsTopView(
                            title = model.title,
                            subtitle = model.subtitle,
                            serverName = model.serverName,
                            showServerButton = model.showServerButton,
                            showQualityButton = model.showQualityButton,
                            onBackClick = callbacks::onBack,
                            onServerClick = callbacks::onServers,
                            onQualityClick = callbacks::onQuality,
                            onAudioClick = callbacks::onAudio,
                            onSubtitleClick = callbacks::onSubtitles,
                            showExternalPlayerButton = model.showExternalPlayerButton,
                            onExternalPlayerClick = callbacks::onExternalPlayer,
                            onMoreClick = callbacks::onMore,
                        )
                    }
                    Spacer(modifier = Modifier.weight(weight = 1f))
                    AnimatedVisibility(
                        visible = controlsShown,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        ControlsBottomView(
                            player = player,
                            progressState = progressState,
                            videoContentScale = videoZoomAndContentScaleState.videoContentScale,
                            isPipSupported = model.isPictureInPictureSupported,
                            onVideoContentScaleClick = {
                                controlsVisibilityState.showControls()
                                videoZoomAndContentScaleState.switchToNextVideoContentScale()
                            },
                            onLockControlsClick = {
                                controlsVisibilityState.showControls()
                                controlsVisibilityState.lockControls()
                            },
                            onPictureInPictureClick = callbacks::onPictureInPicture,
                            onPlaybackSpeedClick = { model.speedSheetVisible = true },
                            onSeek = seekGestureState::onSeek,
                            onSeekEnd = seekGestureState::onSeekEnd,
                        )
                    }
                }
                when {
                    seekGestureState.seekAmount != null -> InfoView(
                        info = "${seekGestureState.seekAmountFormatted}\n" +
                            "[${seekGestureState.seekToPositionFormated}]",
                    )

                    videoZoomAndContentScaleState.isZooming -> InfoView(
                        info = "${(videoZoomAndContentScaleState.zoom * 100).toInt()}%",
                    )

                    videoZoomAndContentScaleState.showContentScaleIndicator -> InfoView(
                        info = stringResource(videoZoomAndContentScaleState.videoContentScale.nameRes()),
                    )

                    controlsVisibilityState.controlsVisible -> ControlsMiddleView(
                        player = player,
                        hasPrevious = model.hasPrevious,
                        isLoading = isLoading,
                        hasNext = model.hasNext,
                        onPreviousClick = {
                            controlsVisibilityState.showControls()
                            callbacks.onPreviousEpisode()
                        },
                        onNextClick = {
                            controlsVisibilityState.showControls()
                            callbacks.onNextEpisode()
                        },
                        modifier = Modifier.thenIf(condition = isTv) {
                            focusRequester(focusRequester = middleControlsFocusRequester)
                                .onFocusChanged { isMiddleControlsFocused = it.hasFocus }
                        },
                    )
                }
            }
        }

        SkipSegmentPill(
            label = model.skipLabel,
            bottomPadding = hostBottomPadding,
            focusRequester = skipFocusRequester,
            onClick = callbacks::onSkipSegment,
        )
        UpNextCard(
            upNext = model.upNext,
            poster = model.upNextPoster,
            bottomPadding = hostBottomPadding,
            focusRequester = upNextFocusRequester,
            onPlay = callbacks::onUpNextPlay,
            onCancel = callbacks::onUpNextCancel,
        )
        PlayerMessage(model = model)

        OverlayShowView(
            player = player,
            overlayView = overlayView,
            picker = model.picker,
            onDismiss = dismissOverlay,
        )

        if (volumeAndBrightnessGestureState != null && brightnessState != null) {
            PlayerVerticalGestureIndicators(
                activeGesture = volumeAndBrightnessGestureState.activeGesture,
                volumePercentage = volumeState.volumePercentage,
                maxVolumePercentage = volumeState.maxVolumePercentage,
                brightnessPercentage = brightnessState.brightnessPercentage,
            )
        }
    }
}

private data class Transform(
    val scale: com.streamflixreborn.streamflix.nextui.model.VideoContentScale,
    val zoom: Float,
    val offsetX: Float,
    val offsetY: Float,
)
