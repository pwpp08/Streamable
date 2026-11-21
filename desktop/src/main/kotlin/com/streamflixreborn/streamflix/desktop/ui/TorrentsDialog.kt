package com.streamflixreborn.streamflix.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.torrent.TorrentBrowser
import com.streamflixreborn.streamflix.torrent.TorrentFilter
import com.streamflixreborn.streamflix.torrent.TorrentOption
import com.streamflixreborn.streamflix.torrent.TorrentSort

/**
 * "Watch with torrents": every torrent Torrentio and the viewer's addons (MediaFusion) have for the
 * title, with filters for resolution, source, codec, HDR, seeders and size. [onPick] starts one.
 */
@Composable
fun TorrentsDialog(
    videoType: Video.Type,
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    onPick: (TorrentOption) -> Unit,
) {
    var all by remember(videoType) { mutableStateOf<List<TorrentOption>?>(null) }
    LaunchedEffect(videoType) { all = runCatching { TorrentBrowser.load(videoType) }.getOrDefault(emptyList()) }

    var resolutions by remember { mutableStateOf(emptySet<Int>()) }
    var sources by remember { mutableStateOf(emptySet<String>()) }
    var codecs by remember { mutableStateOf(emptySet<String>()) }
    var hdrOnly by remember { mutableStateOf(false) }
    var minSeeders by remember { mutableStateOf(0) }
    var maxSize by remember { mutableStateOf<Double?>(null) }
    var sort by remember { mutableStateOf(TorrentSort.SEEDERS) }

    val shown = remember(all, resolutions, sources, codecs, hdrOnly, minSeeders, maxSize, sort) {
        val filter = TorrentFilter(resolutions, sources, codecs, hdrOnly, minSeeders, maxSize)
        TorrentBrowser.sort(TorrentBrowser.filter(all.orEmpty(), filter), sort)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.width(980.dp).height(700.dp),
        ) {
            Column(Modifier.fillMaxSize().padding(28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Watch with torrents", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(
                            listOfNotNull(title, subtitle).joinToString(" · "),
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FilledTonalIconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
                }
                Spacer(Modifier.height(12.dp))

                ChipRow("Resolution") {
                    TorrentBrowser.RESOLUTIONS.forEach { r ->
                        FilterChip(r in resolutions, { resolutions = if (r in resolutions) resolutions - r else resolutions + r }, { Text(resolutionLabel(r)) })
                    }
                }
                ChipRow("Source") {
                    TorrentBrowser.SOURCES.forEach { s ->
                        FilterChip(s in sources, { sources = if (s in sources) sources - s else sources + s }, { Text(s) })
                    }
                }
                ChipRow("Codec") {
                    TorrentBrowser.CODECS.forEach { c ->
                        FilterChip(c in codecs, { codecs = if (c in codecs) codecs - c else codecs + c }, { Text(c) })
                    }
                    FilterChip(hdrOnly, { hdrOnly = !hdrOnly }, { Text("HDR") })
                }
                ChipRow("Seeders") {
                    listOf(0, 5, 20, 100).forEach { n ->
                        FilterChip(minSeeders == n, { minSeeders = n }, { Text(if (n == 0) "Any" else "$n+") })
                    }
                }
                ChipRow("Size") {
                    listOf<Double?>(null, 2.0, 5.0, 10.0, 20.0).forEach { g ->
                        FilterChip(maxSize == g, { maxSize = g }, { Text(if (g == null) "Any" else "≤ ${g.toInt()} GB") })
                    }
                }
                ChipRow("Sort by") {
                    listOf(TorrentSort.SEEDERS to "Seeders", TorrentSort.QUALITY to "Quality", TorrentSort.SIZE to "Size").forEach { (s, label) ->
                        FilterChip(sort == s, { sort = s }, { Text(label) })
                    }
                }

                Spacer(Modifier.height(8.dp))
                val loaded = all
                Text(
                    when {
                        loaded == null -> "Looking for torrents…"
                        loaded.isEmpty() -> "No torrents found for this title."
                        shown.isEmpty() -> "Nothing matches these filters."
                        else -> "${shown.size} of ${loaded.size} torrents"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                if (loaded == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(shown, key = { it.stream.infoHash + ":" + it.stream.fileIdx }) { option -> OptionRow(option) { onPick(option) } }
                    }
                }
            }
        }
    }
}

private fun resolutionLabel(r: Int) = when (r) {
    2160 -> "4K"
    480 -> "SD"
    else -> "${r}p"
}

@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun OptionRow(option: TorrentOption, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(resolutionLabel(option.resolution), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(option.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    buildList {
                        option.source?.let { add(it) }
                        option.codec?.let { add(it) }
                        if (option.hdr) add("HDR")
                        option.audio?.let { add(it) }
                        if (option.stream.sizeBytes > 0) add("%.1f GB".format(java.util.Locale.US, option.sizeGb))
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("▲ ${option.stream.seeders}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(option.addon, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
