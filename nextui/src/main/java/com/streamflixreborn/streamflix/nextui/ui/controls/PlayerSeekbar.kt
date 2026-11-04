package com.streamflixreborn.streamflix.nextui.ui.controls

import androidx.annotation.OptIn
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi

@OptIn(
    ExperimentalMaterial3Api::class,
    UnstableApi::class,
)
@Composable
internal fun PlayerSeekbar(
    modifier: Modifier = Modifier,
    position: Float,
    duration: Float,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
) {
    val currentOnSeekFinished by rememberUpdatedState(newValue = onSeekFinished)
    var isScrubbing by remember { mutableStateOf(value = false) }
    val onValueChange: (Float) -> Unit = { value ->
        isScrubbing = true
        onSeek(value)
    }
    val onValueChangeFinished = {
        if (isScrubbing) {
            isScrubbing = false
            currentOnSeekFinished()
        }
    }
    DisposableEffect(key1 = Unit) {
        // Controls can disappear before a pointer/key release reaches the slider.
        onDispose { onValueChangeFinished() }
    }
    var isFocused by remember { mutableStateOf(value = false) }
    val focusModifier = modifier
        .fillMaxWidth()
        .onFocusChanged {
            if (isFocused && !it.isFocused) onValueChangeFinished()
            isFocused = it.isFocused
        }
    CompositionLocalProvider(value = LocalLayoutDirection provides LayoutDirection.Ltr) {
        SimpleSlider(
            modifier = focusModifier,
            isFocused = isFocused,
            value = position,
            valueRange = 0f..(duration.takeIf { it > 0 } ?: 0f),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleSlider(
    modifier: Modifier = Modifier,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    isFocused: Boolean = false,
) {
    val thumbHeight by animateDpAsState(
        targetValue = if (isFocused) 40.dp else 34.dp,
        label = "thumbHeight",
    )
    Slider(
        value = value,
        valueRange = valueRange,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier.height(40.dp),
        thumb = {
            Box(
                modifier = Modifier
                    .width(if (isFocused) 8.dp else 6.dp)
                    .height(thumbHeight)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        },
        track = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)),
            ) {
                if (valueRange.endInclusive > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = (value / valueRange.endInclusive).coerceIn(0f, 1f))
                            .height(12.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        },
    )
}
