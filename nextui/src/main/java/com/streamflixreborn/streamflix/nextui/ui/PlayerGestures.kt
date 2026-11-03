package com.streamflixreborn.streamflix.nextui.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.streamflixreborn.streamflix.nextui.extensions.detectCustomHorizontalDragGestures
import com.streamflixreborn.streamflix.nextui.extensions.detectCustomTransformGestures
import com.streamflixreborn.streamflix.nextui.extensions.detectCustomVerticalDragGestures
import com.streamflixreborn.streamflix.nextui.state.ControlsVisibilityState
import com.streamflixreborn.streamflix.nextui.state.SeekGestureState
import com.streamflixreborn.streamflix.nextui.state.TapGestureState
import com.streamflixreborn.streamflix.nextui.state.VideoZoomAndContentScaleState
import com.streamflixreborn.streamflix.nextui.state.VolumeAndBrightnessGestureState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerGestures(
    modifier: Modifier = Modifier,
    controlsVisibilityState: ControlsVisibilityState,
    tapGestureState: TapGestureState,
    isInPictureInPictureMode: Boolean,
    seekGestureState: SeekGestureState,
    videoZoomAndContentScaleState: VideoZoomAndContentScaleState,
    volumeAndBrightnessGestureState: VolumeAndBrightnessGestureState?,
) {
    // Keep the system's swipe-to-reveal areas reserved even while the bars are hidden.
    val gestureInsets = WindowInsets.systemGestures.union(WindowInsets.systemBarsIgnoringVisibility)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    BoxWithConstraints {
        Box(
            modifier = modifier
                .fillMaxSize()
                .pointerInput(isInPictureInPictureMode) {
                    if (isInPictureInPictureMode) return@pointerInput

                    detectTapGestures(
                        onTap = {
                            if (tapGestureState.seekMillis != 0L) return@detectTapGestures
                            controlsVisibilityState.toggleControlsVisibility()
                        },
                        onDoubleTap = {
                            if (controlsVisibilityState.controlsLocked) return@detectTapGestures
                            tapGestureState.handleDoubleTap(
                                offset = it,
                                size = size,
                            )
                        },
                        onPress = {
                            tryAwaitRelease()
                            tapGestureState.handleOnLongPressRelease()
                        },
                        onLongPress = {
                            if (controlsVisibilityState.controlsLocked) return@detectTapGestures
                            tapGestureState.handleLongPress(offset = it)
                        },
                    )
                }
                .pointerInput(
                    seekGestureState,
                    gestureInsets,
                    density,
                    layoutDirection,
                    controlsVisibilityState.controlsLocked,
                    isInPictureInPictureMode,
                ) {
                    if (controlsVisibilityState.controlsLocked) return@pointerInput
                    if (isInPictureInPictureMode) return@pointerInput

                    detectCustomHorizontalDragGestures(
                        shouldStartGesture = {
                            gestureInsets.isPlayerGestureStart(
                                it,
                                size,
                                density,
                                layoutDirection,
                            )
                        },
                        onDragStart = seekGestureState::onDragStart,
                        onHorizontalDrag = seekGestureState::onDrag,
                        onDragCancel = seekGestureState::onDragEnd,
                        onDragEnd = seekGestureState::onDragEnd,
                    )
                }
                .pointerInput(
                    gestureInsets,
                    density,
                    layoutDirection,
                    controlsVisibilityState.controlsLocked,
                    isInPictureInPictureMode,
                    volumeAndBrightnessGestureState,
                ) {
                    if (volumeAndBrightnessGestureState == null) return@pointerInput
                    if (controlsVisibilityState.controlsLocked) return@pointerInput
                    if (isInPictureInPictureMode) return@pointerInput

                    detectCustomVerticalDragGestures(
                        shouldStartGesture = {
                            gestureInsets.isPlayerGestureStart(
                                it,
                                size,
                                density,
                                layoutDirection,
                            )
                        },
                        onDragStart = {
                            volumeAndBrightnessGestureState.onDragStart(
                                offset = it,
                                size = size,
                            )
                        },
                        onVerticalDrag = volumeAndBrightnessGestureState::onDrag,
                        onDragCancel = volumeAndBrightnessGestureState::onDragEnd,
                        onDragEnd = volumeAndBrightnessGestureState::onDragEnd,
                    )
                }
                .pointerInput(
                    controlsVisibilityState.controlsLocked,
                    isInPictureInPictureMode,
                ) {
                    if (controlsVisibilityState.controlsLocked) return@pointerInput
                    if (isInPictureInPictureMode) return@pointerInput

                    detectCustomTransformGestures(
                        onGesture = { _, panChange, zoomChange, _ ->
                            if (tapGestureState.isLongPressGestureInAction) {
                                return@detectCustomTransformGestures
                            }
                            videoZoomAndContentScaleState.onZoomPanGesture(
                                constraints = this@BoxWithConstraints.constraints,
                                panChange = panChange,
                                zoomChange = zoomChange,
                            )
                        },
                        onGestureEnd = { videoZoomAndContentScaleState.onZoomPanGestureEnd() },
                    )
                },
        )
    }
}

internal fun WindowInsets.isPlayerGestureStart(
    position: Offset,
    size: IntSize,
    density: Density,
    layoutDirection: LayoutDirection,
): Boolean = position.x >= getLeft(
    density,
    layoutDirection,
) &&
    position.x < size.width - getRight(
        density,
        layoutDirection,
    ) &&
    position.y >= getTop(density) &&
    position.y < size.height - getBottom(density)
