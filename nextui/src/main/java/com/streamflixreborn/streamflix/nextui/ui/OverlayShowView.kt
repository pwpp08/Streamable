package com.streamflixreborn.streamflix.nextui.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.streamflixreborn.streamflix.nextui.Picker
import com.streamflixreborn.streamflix.nextui.R
import com.streamflixreborn.streamflix.nextui.extensions.noRippleClickable

@Composable
fun BoxScope.OverlayShowView(
    player: Player,
    overlayView: OverlayView?,
    picker: Picker?,
    onDismiss: () -> Unit,
) {
    // Scrim that closes the open sheet when tapped outside of it.
    Box(
        modifier = Modifier
            .matchParentSize()
            .then(
                other = if (overlayView != null) {
                    Modifier.noRippleClickable(onClick = onDismiss)
                } else {
                    Modifier
                },
            ),
    )

    PickerView(
        show = overlayView == OverlayView.PICKER,
        picker = picker,
        onDismiss = onDismiss,
    )

    PlaybackSpeedSelectorView(
        show = overlayView == OverlayView.PLAYBACK_SPEED,
        player = player,
    )
}

/** Radio list sheet used for audio, subtitles, servers and quality. */
@Composable
private fun BoxScope.PickerView(show: Boolean, picker: Picker?, onDismiss: () -> Unit) {
    // Keep the last content around so the sheet can animate out.
    var lastPicker by remember { mutableStateOf<Picker?>(null) }
    if (picker != null) lastPicker = picker
    val content = lastPicker ?: return

    OverlayView(
        show = show,
        title = content.title,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(state = rememberScrollState())
                .padding(bottom = 24.dp)
                .padding(horizontal = 24.dp)
                .selectableGroup(),
        ) {
            if (content.items.isEmpty()) {
                Text(
                    text = content.emptyText ?: stringResource(R.string.picker_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            content.items.forEach { item ->
                RadioButtonRow(
                    selected = item.selected,
                    text = item.label,
                    onClick = {
                        onDismiss()
                        item.onSelect()
                    },
                )
            }
            if (content.actions.isNotEmpty()) Spacer(modifier = Modifier.size(16.dp))
            content.actions.forEach { action ->
                FilledTonalButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    onClick = {
                        if (action.dismiss) onDismiss()
                        action.onClick()
                    },
                ) { Text(text = action.label) }
            }
        }
    }
}

val Configuration.isPortrait: Boolean
    get() = orientation == Configuration.ORIENTATION_PORTRAIT

enum class OverlayView {
    PICKER,
    PLAYBACK_SPEED,
}
