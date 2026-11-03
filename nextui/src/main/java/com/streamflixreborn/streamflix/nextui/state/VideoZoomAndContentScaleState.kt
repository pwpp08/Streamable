package com.streamflixreborn.streamflix.nextui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Constraints
import androidx.media3.common.C
import androidx.media3.common.Player
import com.streamflixreborn.streamflix.nextui.model.VideoContentScale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun rememberVideoZoomAndContentScaleState(
    player: Player,
    initialContentScale: VideoContentScale,
    enableZoomGesture: Boolean,
    enablePanGesture: Boolean,
    onContentScaleChanged: (VideoContentScale) -> Unit = {},
): VideoZoomAndContentScaleState {
    val coroutineScope = rememberCoroutineScope()
    return remember(player, enableZoomGesture, enablePanGesture) {
        VideoZoomAndContentScaleState(
            player = player,
            initialContentScale = initialContentScale,
            enableZoomGesture = enableZoomGesture,
            enablePanGesture = enablePanGesture,
            onContentScaleChanged = onContentScaleChanged,
            coroutineScope = coroutineScope,
        )
    }
}

/**
 * Content scale (fit / stretch / crop) plus pinch zoom and pan. The host applies the values to its
 * video view; nothing is written back to the media item.
 */
@Stable
class VideoZoomAndContentScaleState(
    private val player: Player,
    initialContentScale: VideoContentScale,
    private val enableZoomGesture: Boolean = true,
    private val enablePanGesture: Boolean = true,
    private val onContentScaleChanged: (VideoContentScale) -> Unit,
    private val coroutineScope: CoroutineScope,
) {
    companion object {
        private const val MIN_ZOOM = 0.25f
        private const val MAX_ZOOM = 4f
        private const val CONTENT_SCALE_INDICATOR_DURATION_MS = 1000L
        private val CYCLE = listOf(
            VideoContentScale.BEST_FIT,
            VideoContentScale.CROP,
            VideoContentScale.STRETCH,
        )
    }

    var videoContentScale: VideoContentScale by mutableStateOf(initialContentScale)
        private set

    var zoom: Float by mutableFloatStateOf(1f)
        private set

    var offset: Offset by mutableStateOf(Offset.Zero)
        private set

    var isZooming: Boolean by mutableStateOf(false)
        private set

    var showContentScaleIndicator: Boolean by mutableStateOf(false)
        private set

    private var showContentScaleJob: Job? = null

    fun onVideoContentScaleChanged(newContentScale: VideoContentScale) {
        videoContentScale = newContentScale
        zoom = 1f
        offset = Offset.Zero
        onContentScaleChanged(newContentScale)
        showContentScaleJob?.cancel()
        showContentScaleIndicator = true
        showContentScaleJob = coroutineScope.launch {
            delay(CONTENT_SCALE_INDICATOR_DURATION_MS)
            showContentScaleIndicator = false
        }
    }

    fun switchToNextVideoContentScale() {
        val index = CYCLE.indexOf(videoContentScale)
        onVideoContentScaleChanged(CYCLE[(index + 1) % CYCLE.size])
    }

    fun onZoomPanGesture(constraints: Constraints, panChange: Offset, zoomChange: Float) {
        if (player.duration == C.TIME_UNSET) return
        if (!enableZoomGesture) return

        isZooming = true
        zoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)

        val maxX = abs((zoom - 1) * constraints.maxWidth / 2)
        val maxY = abs((zoom - 1) * constraints.maxHeight / 2)

        if (enablePanGesture) {
            offset = Offset(
                x = (offset.x + zoom * panChange.x).coerceIn(-maxX, maxX),
                y = (offset.y + zoom * panChange.y).coerceIn(-maxY, maxY),
            )
        }
    }

    fun onZoomPanGestureEnd() {
        isZooming = false
    }
}
