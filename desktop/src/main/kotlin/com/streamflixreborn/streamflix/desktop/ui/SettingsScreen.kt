package com.streamflixreborn.streamflix.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.desktop.data.Repo
import com.streamflixreborn.streamflix.desktop.player.VlcEngine
import com.streamflixreborn.streamflix.utils.UserPreferences

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 28.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 10.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { content() }
        }
    }
}

@Composable
private fun SettingRow(title: String, summary: String? = null, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        trailing()
    }
}

@Composable
fun SettingsScreen(onThemeChanged: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev by UserPreferences.revision.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp)) {
        Column(Modifier.widthIn(max = 820.dp)) {
            ScreenHeader("Settings")

            SettingsGroup("Playback") {
                SettingRow("Maximum quality", "4K first when a server has it; 1080p is the minimum.") {
                    ConnectedToggle(listOf(2160, 1440, 1080), UserPreferences.maxResolution, { if (it == 2160) "4K" else "${it}p" }) {
                        UserPreferences.maxResolution = it
                    }
                }
                SettingRow("Play the next episode automatically") {
                    Switch(UserPreferences.autoplayNext, { UserPreferences.autoplayNext = it })
                }
                var subLang by remember { mutableStateOf(UserPreferences.subtitleLanguage) }
                SettingRow("Subtitles on by default", "Language to switch on when a server offers it (e.g. English). Empty = off.") {
                    OutlinedTextField(subLang, { subLang = it; UserPreferences.subtitleLanguage = it.trim() }, singleLine = true, modifier = Modifier.width(200.dp))
                }
            }

            SettingsGroup("Appearance") {
                SettingRow("Amoled black", "True black backgrounds, as in the phone app’s Amoled palette.") {
                    Switch(UserPreferences.themeMode == "amoled", {
                        UserPreferences.themeMode = if (it) "amoled" else "dark"
                        onThemeChanged()
                    })
                }
            }

            SettingsGroup("Sources") {
                var key by remember { mutableStateOf(UserPreferences.tmdbApiKey) }
                SettingRow("TMDB API key", "Leave empty to use the built-in key.") {
                    OutlinedTextField(key, { key = it; UserPreferences.tmdbApiKey = it.trim() }, singleLine = true, modifier = Modifier.width(320.dp))
                }
                SettingRow("Clear cached pages", "Reloads home rows and details from the provider.") {
                    TextButton(onClick = { Repo.clear() }) { Text("Clear") }
                }
            }

            SettingsGroup("Player engine") {
                val status = if (VlcEngine.available) "VLC is ready" else "VLC not found: ${VlcEngine.error ?: "install VLC from videolan.org"}"
                var path by remember { mutableStateOf(UserPreferences.vlcPath) }
                SettingRow("VLC", status) {}
                SettingRow("VLC folder", "Only needed if VLC is installed somewhere unusual. Restart after changing.") {
                    OutlinedTextField(path, { path = it; UserPreferences.vlcPath = it.trim() }, singleLine = true, modifier = Modifier.width(320.dp))
                }
            }

            SettingsGroup("About") {
                SettingRow("StreamFlix for desktop", "Version ${BuildConfig.VERSION_NAME}") {}
                SettingRow("Data folder", StreamFlixApp.instance.dataDir.path) {
                    Button(onClick = { runCatching { java.awt.Desktop.getDesktop().open(StreamFlixApp.instance.dataDir) } }) { Text("Open") }
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}
