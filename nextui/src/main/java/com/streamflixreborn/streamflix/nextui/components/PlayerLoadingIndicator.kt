package com.streamflixreborn.streamflix.nextui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.material.loadingindicator.LoadingIndicator

private val ContainerSize = 84.dp
private val IndicatorSize = 60.dp

/**
 * M3 Expressive contained loading indicator (morphing shape on a round container), sized to
 * stand in for the play/pause button. Uses the MDC view, as the Compose one isn't public yet.
 */
@Composable
fun PlayerLoadingIndicator(modifier: Modifier = Modifier) {
    val container = MaterialTheme.colorScheme.primaryContainer.toArgb()
    val indicator = MaterialTheme.colorScheme.onPrimaryContainer.toArgb()
    val density = LocalDensity.current
    val containerPx = with(density) { ContainerSize.roundToPx() }
    val indicatorPx = with(density) { IndicatorSize.roundToPx() }
    AndroidView(
        modifier = modifier.size(ContainerSize),
        factory = { context -> LoadingIndicator(context) },
        update = { view ->
            view.setContainerWidth(containerPx)
            view.setContainerHeight(containerPx)
            view.setIndicatorSize(indicatorPx)
            view.setContainerColor(container)
            view.setIndicatorColor(indicator)
        },
    )
}
