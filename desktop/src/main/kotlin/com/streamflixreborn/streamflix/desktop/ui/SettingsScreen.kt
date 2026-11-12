package com.streamflixreborn.streamflix.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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

/** The ten most spoken subtitle languages (plus Hungarian), as VLC names them. */
private val SUBTITLE_LANGUAGES = listOf(
    "English", "Spanish", "French", "German", "Portuguese", "Italian", "Russian", "Arabic", "Turkish", "Hungarian",
)

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
                SettingRow("Upgrade to 4K in the background", "Playback starts at 1080p. A 4K server is tested quietly and, only if it holds up, swapped in without a break.") {
                    Switch(UserPreferences.upgrade4k, { UserPreferences.upgrade4k = it })
                }
                SettingRow("Play the next episode automatically") {
                    Switch(UserPreferences.autoplayNext, { UserPreferences.autoplayNext = it })
                }
                var subLang by remember { mutableStateOf(UserPreferences.subtitleLanguage) }
                SettingRow("Subtitles", "Switched on automatically when a server offers this language.") {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { open = true }) {
                            Text(subLang.ifBlank { "Off" })
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Rounded.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            listOf("") .plus(SUBTITLE_LANGUAGES).forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang.ifBlank { "Off" }) },
                                    trailingIcon = { if (lang == subLang) Icon(Icons.Rounded.Check, null) },
                                    onClick = { subLang = lang; UserPreferences.subtitleLanguage = lang; open = false },
                                )
                            }
                        }
                    }
                }
            }

            SettingsGroup("Appearance") {
                SettingRow("Colours", "The same palettes as the phone app.") {
                    ConnectedToggle(listOf("sage", "crimson", "amoled"), UserPreferences.themeMode, { it.replaceFirstChar(Char::uppercase) }) {
                        UserPreferences.themeMode = it
                        onThemeChanged()
                    }
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
                val update = com.streamflixreborn.streamflix.desktop.Updater.state
                SettingRow(
                    "StreamFlix for desktop",
                    "Version ${BuildConfig.VERSION_NAME}" + when (update) {
                        is com.streamflixreborn.streamflix.desktop.Updater.State.Available -> " · ${update.release.version} is available"
                        is com.streamflixreborn.streamflix.desktop.Updater.State.Downloading -> " · downloading ${update.release.version}…"
                        com.streamflixreborn.streamflix.desktop.Updater.State.UpToDate -> " · up to date"
                        else -> ""
                    },
                ) {
                    if (com.streamflixreborn.streamflix.desktop.Updater.supported) {
                        if (update is com.streamflixreborn.streamflix.desktop.Updater.State.Available) {
                            Button(onClick = { com.streamflixreborn.streamflix.desktop.Updater.install() }) { Text("Update") }
                        } else {
                            TextButton(onClick = { com.streamflixreborn.streamflix.desktop.Updater.checkNow() }) { Text("Check for updates") }
                        }
                    }
                }
                SettingRow("Data folder", StreamFlixApp.instance.dataDir.path) {
                    Button(onClick = { runCatching { java.awt.Desktop.getDesktop().open(StreamFlixApp.instance.dataDir) } }) { Text("Open") }
                }
                SettingRow("Quit StreamFlix", "Or press Alt+F4.") {
                    TextButton(onClick = {
                        com.streamflixreborn.streamflix.desktop.data.Library.flush()
                        kotlin.system.exitProcess(0)
                    }) { Text("Quit") }
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}
