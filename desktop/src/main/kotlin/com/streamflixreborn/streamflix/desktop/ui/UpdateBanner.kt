package com.streamflixreborn.streamflix.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamflixreborn.streamflix.desktop.Updater

private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

/** A card in the corner when a newer version is out. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val state = Updater.state
    val visible = !Updater.dismissed && (state is Updater.State.Available || state is Updater.State.Downloading || state is Updater.State.Failed)
    AnimatedVisibility(visible, modifier, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(max = 380.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SystemUpdateAlt, null)
                    Spacer(Modifier.width(12.dp))
                    val version = when (state) {
                        is Updater.State.Available -> state.release.version
                        is Updater.State.Downloading -> state.release.version
                        is Updater.State.Failed -> state.release.version
                        else -> ""
                    }
                    Text("StreamFlix $version is available", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                when (state) {
                    is Updater.State.Downloading -> {
                        Text("Downloading… ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { state.progress })
                    }
                    else -> {
                        Text(
                            when {
                                state is Updater.State.Failed -> "The update didn't work: ${state.message}"
                                isMac -> "The new version downloads and opens; drag StreamFlix into Applications to replace this one."
                                else -> "StreamFlix closes, updates and opens again. Takes about a minute."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { Updater.install() }) { Text(if (state is Updater.State.Failed) "Try again" else "Update") }
                            TextButton(onClick = { Updater.dismissed = true }) { Text("Later") }
                        }
                    }
                }
            }
        }
    }
}
