package com.Music

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.Music.data.local.SongEntity
import com.Music.downloader.SearchResult
import kotlinx.coroutines.delay

/**
 * An in-app YouTube search screen, reachable from the Library top-bar
 * YouTube chooser ("Search YouTube"). Lets the user type a query and see flat
 * results (thumbnail / title / uploader / duration) render automatically as
 * they type (debounced ~500 ms). Tapping any result **copies its
 * `watch?v=<id>` link** to the system clipboard, after which they can paste it
 * into the Add-Music URL field via the "+" button.
 *
 * Search runs through [MainViewModel.searchYouTube], which delegates to
 * yt-dlp's `ytsearch` extractor (YouTube) — no API key, handles YouTube's
 * anti-bot.
 *
 * Uses cyan primary accents for icons and headings (rather than filling
 * containers with primaryContainer), and reserves 84 dp at the bottom when
 * a song is loaded so the morphing PlayerOverlay's mini bar doesn't overlap
 * the results list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeSearchScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.youtubeSearch.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // The morphing PlayerOverlay pins a MiniPlayer bar on top of this screen
    // when a song is loaded. Reserve matching bottom space so the last results
    // aren't hidden behind it (same 84.dp inset LibraryScreen uses).
    val miniPlayerInset = if (currentSong != null) 84.dp else 0.dp

    // Clear any previous results whenever we leave the screen so a return
    // visit starts fresh.
    DisposableEffect(Unit) {
        onDispose { viewModel.clearYouTubeSearch() }
    }

    BackHandler { onBack() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Auto-search with debounce: fire ~500ms after the user stops typing, so
    // they no longer need to press the Search IME action. Re-launching on every
    // keystroke cancels the previous delay, giving natural debounce behavior.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            viewModel.clearYouTubeSearch()
            return@LaunchedEffect
        }
        delay(500)
        viewModel.searchYouTube(query.trim())
    }

    fun copyLink(result: SearchResult) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("YouTube link", result.url))
        Toast.makeText(context, "Link copied — paste it in Add Music", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search YouTube", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top    = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + miniPlayerInset
                )
        ) {
            // ── Search bar: pill-shaped field, primaryContainer accent ──────
            OutlinedTextField(
                value         = query,
                onValueChange = { query = it },
                modifier      = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .focusRequester(focusRequester),
                placeholder   = { Text("Search for a song or artist") },
                singleLine    = true,
                shape         = RoundedCornerShape(28.dp),
                leadingIcon   = {
                    Icon(
                        Icons.Default.Search, null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon  = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            viewModel.clearYouTubeSearch()
                        }) {
                            Icon(
                                Icons.Default.Clear, "Clear",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { viewModel.searchYouTube(query.trim()) }
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Search
                )
            )

            // ── Body: loading / error / empty / results ───────────────────
            Box(
                Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                when {
                    state.isLoading -> {
                        Column(
                            Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                Modifier.size(36.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Searching…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    state.error != null -> {
                        Column(
                            Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.LibraryMusic, null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                state.error!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    state.results.isEmpty() && state.query.isNotEmpty() -> {
                        Column(
                            Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.LibraryMusic, null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "No results for \"${state.query}\"",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    state.results.isEmpty() -> {
                        Column(
                            Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Search, null,
                                modifier = Modifier.size(72.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Search YouTube",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Type a song or artist — results load automatically.\n" +
                                "Tap a result to copy its link, then paste via +",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(
                                start = 12.dp, end = 12.dp, top = 4.dp, bottom = 20.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.results, key = { it.url }) { result ->
                                SearchResultRow(result) { copyLink(result) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    result: SearchResult,
    onCopy: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail (64dp, rounded), neutral placeholder.
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (result.thumbnailUrl != null) {
                    AsyncImage(
                        model              = result.thumbnailUrl,
                        contentDescription = null,
                        modifier           = Modifier.fillMaxSize(),
                        contentScale       = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.LibraryMusic, null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Title + (uploader · duration chip).
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        result.uploader,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (result.duration > 0) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                formatDuration(result.duration),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(4.dp))

            // Copy action.
            IconButton(onClick = onCopy) {
                Icon(
                    Icons.Default.ContentCopy, "Copy link",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
