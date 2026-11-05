package com.streamflixreborn.streamflix.desktop.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamflixreborn.streamflix.desktop.Nav
import com.streamflixreborn.streamflix.desktop.PlayRequest
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.delay
import org.jetbrains.skia.Rect
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

private enum class Fit(val label: String) { FIT("Fit"), FILL("Fill"), STRETCH("Stretch") }

private val hiddenCursor by lazy {
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "hidden"))
}

private fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(request: PlayRequest, fullscreen: Boolean, onToggleFullscreen: () -> Unit) {
    val controller = remember(request) { PlayerController(request) }
    DisposableEffect(controller) { onDispose { controller.release() } }

    var frameTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controller) {
        var seen = -1L
        while (true) {
            withFrameNanos { }
            val v = controller.frames.version.get()
            if (v != seen) {
                seen = v
                frameTick = v
            }
        }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var lastMove by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var menuOpen by remember { mutableStateOf(false) }
    var fit by remember { mutableStateOf(Fit.FIT) }
    LaunchedEffect(lastMove, controller.playing, menuOpen) {
        controlsVisible = true
        if (controller.playing && !menuOpen) {
            delay(3_000)
            controlsVisible = false
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // Next episode: offered in the last 40 seconds and played automatically at the end.
    var next by remember(request) { mutableStateOf<PlayRequest?>(null) }
    LaunchedEffect(request) { next = controller.nextEpisode() }
    val nearEnd = controller.lengthMs > 0 && controller.lengthMs - controller.timeMs < 40_000 && controller.timeMs > 0
    LaunchedEffect(controller.finished, next) {
        val n = next
        if (controller.finished && n != null && UserPreferences.autoplayNext) {
            delay(1500)
            Nav.play(n)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastMove = System.currentTimeMillis()
                when (e.key) {
                    Key.Spacebar, Key.K -> { controller.togglePause(); true }
                    Key.DirectionLeft, Key.J -> { controller.seekBy(-10_000); true }
                    Key.DirectionRight, Key.L -> { controller.seekBy(10_000); true }
                    Key.DirectionUp -> { controller.changeVolume(controller.volume + 5); true }
                    Key.DirectionDown -> { controller.changeVolume(controller.volume - 5); true }
                    Key.M -> { controller.toggleMute(); true }
                    Key.F -> { onToggleFullscreen(); true }
                    Key.N -> { next?.let(Nav::play); true }
                    Key.Escape -> { if (fullscreen) onToggleFullscreen() else Nav.back(); true }
                    else -> false
                }
            }
            .onPointerEvent(PointerEventType.Move) { lastMove = System.currentTimeMillis() }
            .pointerHoverIcon(if (controlsVisible) PointerIcon.Default else hiddenCursor)
            .pointerInput(controller) {
                detectTapGestures(
                    onTap = { controller.togglePause(); lastMove = System.currentTimeMillis() },
                    onDoubleTap = { onToggleFullscreen() },
                )
            },
    ) {
        // The picture.
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") frameTick
            val frame = controller.frames.latest() ?: return@Canvas
            val iw = frame.width.toFloat()
            val ih = frame.height.toFloat()
            val scale = when (fit) {
                Fit.FIT -> minOf(size.width / iw, size.height / ih)
                Fit.FILL -> maxOf(size.width / iw, size.height / ih)
                Fit.STRETCH -> 0f
            }
            val dw = if (fit == Fit.STRETCH) size.width else iw * scale
            val dh = if (fit == Fit.STRETCH) size.height else ih * scale
            val left = (size.width - dw) / 2
            val top = (size.height - dh) / 2
            drawIntoCanvas { c -> YuvRenderer.draw(c.nativeCanvas, frame, Rect.makeXYWH(left, top, dw, dh)) }
        }

        val error = controller.error
        if (error != null) {
            Column(Modifier.align(Alignment.Center).widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Can’t play this", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text(error, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.8f))
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (controller.sources.any { it.video != null }) {
                        FilledTonalButton(onClick = { menuOpen = true; lastMove = System.currentTimeMillis() }) { Text("Pick a server") }
                    }
                    Button(onClick = { Nav.back() }) { Text("Back") }
                }
            }
        } else if (controller.loading && !controlsVisible) {
            // The expressive loader stands in for the play button while a server is found or loads.
            ContainedLoadingIndicator(Modifier.align(Alignment.Center).size(96.dp))
        }

        AnimatedVisibility(controlsVisible || controller.loading || error != null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                // Scrims.
                Box(Modifier.fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))))
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(220.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))),
                )

                // Top bar: back, title, server.
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { Nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(request.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        request.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f), maxLines = 1) }
                    }
                    ServerMenu(controller, menuOpen, onOpen = { menuOpen = it })
                }

                // Centre: -10, play/pause (or the loader), +10.
                if (error == null) {
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                        CenterButton(Icons.Rounded.Replay10, "Back 10 seconds") { controller.seekBy(-10_000) }
                        if (controller.loading) {
                            ContainedLoadingIndicator(Modifier.size(96.dp))
                        } else {
                            FilledIconButton(
                                onClick = { controller.togglePause() },
                                modifier = Modifier.size(96.dp),
                                shape = if (controller.playing) MaterialTheme.shapes.extraLarge else CircleShape,
                            ) {
                                Icon(
                                    when {
                                        controller.finished -> Icons.Rounded.Replay
                                        controller.playing -> Icons.Rounded.Pause
                                        else -> Icons.Rounded.PlayArrow
                                    },
                                    if (controller.playing) "Pause" else "Play",
                                    Modifier.size(48.dp),
                                )
                            }
                        }
                        CenterButton(Icons.Rounded.Forward10, "Forward 10 seconds") { controller.seekBy(10_000) }
                    }
                    if (controller.loading && controller.status.isNotBlank()) {
                        Text(
                            controller.status,
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.align(Alignment.Center).padding(top = 170.dp),
                        )
                    }
                }

                // Bottom: seek bar and the control row.
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp)) {
                    if (next != null && nearEnd) {
                        FilledTonalButton(onClick = { Nav.play(next!!) }, modifier = Modifier.align(Alignment.End)) {
                            Icon(Icons.Rounded.SkipNext, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Next episode")
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    SeekBar(controller)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { controller.togglePause() }) {
                            Icon(if (controller.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White)
                        }
                        if (next != null) IconButton(onClick = { Nav.play(next!!) }) { Icon(Icons.Rounded.SkipNext, "Next episode", tint = Color.White) }
                        IconButton(onClick = { controller.toggleMute() }) {
                            Icon(if (controller.muted || controller.volume == 0) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, "Mute", tint = Color.White)
                        }
                        Slider(
                            value = if (controller.muted) 0f else controller.volume.toFloat(),
                            onValueChange = { controller.changeVolume(it.toInt()) },
                            valueRange = 0f..150f,
                            modifier = Modifier.width(140.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text("${formatTime(controller.timeMs)} / ${formatTime(controller.lengthMs)}", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        if (controller.videoHeight > 0) {
                            Text(
                                Source.qualityName(controller.videoHeight),
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.background(Color.White.copy(alpha = 0.16f), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        TrackMenu(Icons.Rounded.ClosedCaption, "Subtitles", controller.subtitleTracks, controller.subtitleTrack, allowOff = true, onOpen = { menuOpen = it }) { controller.selectSubtitle(it) }
                        TrackMenu(Icons.Rounded.Audiotrack, "Audio", controller.audioTracks, controller.audioTrack, allowOff = false, onOpen = { menuOpen = it }) { controller.selectAudio(it) }
                        TextButton(onClick = { fit = Fit.entries[(fit.ordinal + 1) % Fit.entries.size] }) {
                            Icon(Icons.Rounded.AspectRatio, null, tint = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text(fit.label, color = Color.White)
                        }
                        IconButton(onClick = onToggleFullscreen) {
                            Icon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, "Fullscreen", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(64.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.35f), contentColor = Color.White),
    ) { Icon(icon, label, Modifier.size(34.dp)) }
}

@Composable
private fun SeekBar(controller: PlayerController) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val length = controller.lengthMs.coerceAtLeast(1)
    val value = if (dragging) dragValue else controller.timeMs.toFloat() / length
    Slider(
        value = value.coerceIn(0f, 1f),
        onValueChange = { dragging = true; dragValue = it },
        onValueChangeFinished = {
            controller.seekTo((dragValue * length).toLong())
            dragging = false
        },
        enabled = controller.lengthMs > 0,
        colors = SliderDefaults.colors(inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ServerMenu(controller: PlayerController, open: Boolean, onOpen: (Boolean) -> Unit) {
    Box {
        FilledTonalButton(onClick = { onOpen(true) }) {
            Icon(Icons.Rounded.Dns, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            val cur = controller.current
            Text(cur?.let { it.server.name + " · " + it.label } ?: "Servers", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { onOpen(false) }) {
            Text(
                "Servers · best first",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val ranked = Sources.rank(controller.sources)
            val rest = controller.sources.filter { s -> ranked.none { it.order == s.order } }
            (ranked + rest).forEach { src ->
                val isCurrent = controller.current?.order == src.order
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(src.server.name, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(16.dp))
                            Text(src.label, color = if (src.state is Source.State.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    },
                    leadingIcon = {
                        when {
                            isCurrent -> Icon(Icons.Rounded.Check, null)
                            src.state is Source.State.Pending -> LoadingIndicator(Modifier.size(24.dp))
                            else -> Spacer(Modifier.size(24.dp))
                        }
                    },
                    enabled = src.video != null,
                    onClick = {
                        onOpen(false)
                        controller.play(src)
                    },
                )
            }
        }
    }
}

@Composable
private fun TrackMenu(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tracks: List<Track>,
    selected: Int,
    allowOff: Boolean,
    onOpen: (Boolean) -> Unit,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true; onOpen(true) }, enabled = tracks.isNotEmpty()) {
            Icon(icon, label, tint = if (tracks.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.35f))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false; onOpen(false) }) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (allowOff) {
                DropdownMenuItem(
                    text = { Text("Off") },
                    leadingIcon = { if (selected < 0) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                    onClick = { onSelect(-1); open = false; onOpen(false) },
                )
                HorizontalDivider()
            }
            tracks.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.name) },
                    leadingIcon = { if (t.id == selected) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                    onClick = { onSelect(t.id); open = false; onOpen(false) },
                )
            }
        }
    }
}
