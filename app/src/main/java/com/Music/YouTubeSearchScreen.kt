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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.Music.downloader.SearchResult

/**
 * An in-app YouTube search screen, reachable from the Library top-bar
 * YouTube chooser ("Search YouTube"). Lets the user type a query, see flat
 * results (thumbnail / title / uploader / duration), and tap any result to
 * **copy its `watch?v=<id>` link** to the system clipboard — after which they
 * can paste it into the Add-Music URL field via the "+" button.
 *
 * The actual search runs through [MainViewModel.searchYouTube], which delegates
 * to yt-dlp's `ytsearch` extractor (no API key, handles YouTube's anti-bot).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeSearchScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.youtubeSearch.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // Clear any previous results whenever we leave the screen so a return
    // visit starts fresh.
    DisposableEffect(Unit) {
        onDispose { viewModel.clearYouTubeSearch() }
    }

    BackHandler { onBack() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        viewModel.searchYouTube(q)
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
                .padding(padding)
        ) {
            // ── Search bar ────────────────────────────────────────────────
            OutlinedTextField(
                value         = query,
                onValueChange = { query = it },
                modifier      = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .focusRequester(focusRequester),
                placeholder   = { Text("Search for a song or artist") },
                singleLine    = true,
                leadingIcon   = { Icon(Icons.Default.Search, null) },
                trailingIcon  = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            viewModel.clearYouTubeSearch()
                        }) { Icon(Icons.Default.Clear, "Clear") }
                    }
                },
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { runSearch() }
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
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
                        CircularProgressIndicator(
                            Modifier
                                .align(Alignment.Center)
                                .size(28.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    state.error != null -> {
                        Text(
                            state.error!!,
                            Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    state.results.isEmpty() && state.query.isNotEmpty() -> {
                        Text(
                            "No results for \"${state.query}\"",
                            Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.results.isEmpty() -> {
                        Text(
                            "Type a song or artist name and press Search.\n" +
                            "Tap a result to copy its YouTube link.",
                            Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 16.dp)
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
    ListItem(
        modifier = Modifier.clickable { onCopy() },
        headlineContent = {
            Text(
                result.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium
            )
        },
        supportingContent = {
            Text(
                buildString {
                    append(result.uploader)
                    if (result.duration > 0) {
                        val m = result.duration / 60
                        val s = result.duration % 60
                        append(" · %d:%02d".format(m, s))
                    }
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
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
                    Icon(Icons.Default.Search, null)
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, "Copy link")
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
