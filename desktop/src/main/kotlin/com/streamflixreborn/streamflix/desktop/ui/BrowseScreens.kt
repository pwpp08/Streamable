package com.streamflixreborn.streamflix.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.desktop.data.Library
import com.streamflixreborn.streamflix.desktop.data.Repo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * An endless poster grid fed page by page. [header] scrolls with the grid.
 */
@Composable
fun PagedPosterGrid(
    key: Any,
    load: suspend (page: Int) -> List<AppAdapter.Item>,
    state: LazyGridState = rememberLazyGridState(),
    header: (@Composable () -> Unit)? = null,
    emptyTitle: String = "Nothing here",
    emptyMessage: String = "",
) {
    val posters = remember(key) { mutableStateListOf<PosterInfo>() }
    var page by remember(key) { mutableIntStateOf(0) }
    var loading by remember(key) { mutableStateOf(true) }
    var ended by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }

    suspend fun next() {
        if (ended) return
        loading = true
        val result = runCatching { load(page + 1) }
        result.onSuccess { list ->
            val fresh = list.mapNotNull { it.posterInfo() }.filter { p -> posters.none { it.id == p.id && it.isTv == p.isTv } }
            if (fresh.isEmpty()) ended = true
            posters += fresh
            page++
        }.onFailure { error = it.message ?: it.toString(); ended = true }
        loading = false
    }

    LaunchedEffect(key) { next() }
    LaunchedEffect(key, state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= posters.size - 12 && !loading && !ended }
            .collect { next() }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        state = state,
        contentPadding = PaddingValues(start = 32.dp, end = 32.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items(posters, key = { (if (it.isTv) "t" else "m") + it.id }) { PosterCard(it, width = 200.dp) }
        if (loading) item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(48.dp)) }
        }
        if (!loading && posters.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.height(360.dp)) { EmptyState(emptyTitle, error ?: emptyMessage) }
        }
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 24.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MoviesScreen() = PagedPosterGrid("movies", { Repo.movies(it) }, header = { ScreenHeader("Movies", "Popular right now") })

@Composable
fun ShowsScreen() = PagedPosterGrid("shows", { Repo.shows(it) }, header = { ScreenHeader("TV Shows", "Popular right now") })

@Composable
fun GenreScreen(id: String, name: String) = PagedPosterGrid("genre:$id", { Repo.genre(id, it).shows }, header = { ScreenHeader(name) })

@Composable
fun PersonScreen(id: String, name: String) {
    var bio by remember(id) { mutableStateOf<String?>(null) }
    PagedPosterGrid(
        "person:$id",
        { page -> Repo.person(id, page).also { if (page == 1) bio = it.biography }.filmography },
        header = {
            Column(Modifier.padding(top = 32.dp, bottom = 24.dp).widthIn(max = 900.dp)) {
                Text(name, style = MaterialTheme.typography.headlineLarge)
                bio?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6)
                }
            }
        },
    )
}

private var lastQuery = ""

@Composable
fun SearchScreen() {
    var query by rememberSaveable { mutableStateOf(lastQuery) }
    var submitted by remember { mutableStateOf(lastQuery) }
    val history by Library.searches.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Search as you type, after a short pause.
    LaunchedEffect(query) {
        if (query.isBlank()) { submitted = ""; return@LaunchedEffect }
        delay(450)
        submitted = query.trim()
        lastQuery = submitted
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search movies and shows") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") }
            },
            singleLine = true,
            shape = CircleShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { Library.addSearch(query) }),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 32.dp).widthIn(max = 720.dp).fillMaxWidth().focusRequester(focus),
        )
        if (submitted.isBlank()) {
            if (history.isNotEmpty()) {
                Row(Modifier.padding(start = 32.dp, top = 24.dp, end = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent searches", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.size(12.dp))
                    TextButton(onClick = { Library.clearSearches() }) { Text("Clear") }
                }
                FlowRow(Modifier.padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    history.forEach { h ->
                        AssistChip(onClick = { query = h }, label = { Text(h) }, leadingIcon = { Icon(Icons.Rounded.History, null, Modifier.size(18.dp)) })
                    }
                }
            } else {
                EmptyState("Find something to watch", "Type a title, and results show up as you go.")
            }
        } else {
            Spacer(Modifier.height(24.dp))
            PagedPosterGrid(
                "search:$submitted",
                { page -> Repo.search(submitted, page).also { if (page == 1) Library.addSearch(submitted) } },
                emptyTitle = "No results",
                emptyMessage = "Nothing matched “$submitted”.",
            )
        }
    }
}
