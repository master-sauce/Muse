package com.Music

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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

    // A single live preview-toast ref hoisted to the screen so a new long-press
    // cancels the previous card's toast before showing its own — only one
    // title preview is ever on screen at a time.
    val previewToast = remember { mutableStateOf<Toast?>(null) }
    val haptic = LocalHapticFeedback.current
    fun showPreview(title: String) {
        previewToast.value?.cancel()
        val toast = Toast.makeText(context, title, Toast.LENGTH_LONG)
        previewToast.value = toast
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        toast.show()
    }
    // Cancel any lingering toast when leaving the screen so it doesn't outlive
    // the search UI.
    DisposableEffect(Unit) {
        onDispose { previewToast.value?.cancel() }
    }

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
                // Mirror text direction for RTL queries (e.g. Hebrew/Arabic):
                // the typed text and cursor flip to the start side while the
                // leading/trailing icons stay put (Compose already mirrors
                // the surrounding Row/layout under an RTL locale).
                textStyle     = MaterialTheme.typography.bodyLarge.copy(
                    textDirection = androidx.compose.ui.text.style.TextDirection.Content
                ),
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
                                "Tap a result to copy its link, then paste via +\n" +
                                "Tap ▶ to hear a 30s preview, or ⬇ to download",
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
                                SearchResultRow(
                                    result,
                                    // Tap card = copy link. Play-icon button = fetch a
                                    // 30s audio clip (tmp) + open the preview popup.
                                    onTap = { copyLink(result) },
                                    onPreview = { viewModel.startPreview(result) },
                                    onDownload = { viewModel.downloadSong(result.url) },
                                    onLongPress = { showPreview(result.title) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Preview player popup ───────────────────────────────────────────
        // Shown whenever a preview is active (loading / ready / error). A
        // centered dialog floats over the results; it stays up even while a
        // new search runs behind it. Dismissed via its X button / outside tap.
        val preview by viewModel.preview.collectAsState()
        if (preview !is PreviewState.Idle) {
            PreviewPlayerDialog(viewModel = viewModel)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultRow(
    result: SearchResult,
    onTap: () -> Unit,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
    onLongPress: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Long-press shows an Android Toast with the full (untruncated)
            // title so the user can read titles that don't fit in the 2-line
            // card. The previous card's toast is cancelled by the caller so
            // only one preview is on screen at a time. Tap still copies the
            // link, as before.
            .combinedClickable(
                onClick = { onTap() },
                onLongClick = onLongPress
            ),
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

            // Preview (play icon) + download actions. Tapping the card itself
            // copies the link; this button fetches a 30s audio clip (tmp) and
            // opens the preview popup so the user can hear the song first.
            IconButton(onClick = onPreview) {
                Icon(
                    Icons.Default.PlayArrow, "Preview 30s",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onDownload) {
                Icon(
                    Icons.Default.Download, "Download",
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

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

/**
 * A centered popup mini player for the 30-second search-result preview.
 *
 * Shown whenever a preview is active. Renders three states:
 *  - [PreviewState.Loading] : spinner + "Fetching preview…".
 *  - [PreviewState.Ready]   : thumbnail/title, play/pause, seek slider, times.
 *  - [PreviewState.Error]   : inline error text.
 *
 * The clip is a tmp audio file (cacheDir/previews), never added to the
 * library. The X button, an outside tap, or a new preview dismisses it via
 * [MainViewModel.dismissPreview], which stops playback and deletes the file.
 */
@Composable
private fun PreviewPlayerDialog(viewModel: MainViewModel) {
    val preview  by viewModel.preview.collectAsState()
    val playing  by viewModel.previewPlaying.collectAsState()
    val position by viewModel.previewPosition.collectAsState()
    val duration by viewModel.previewDuration.collectAsState()

    if (preview is PreviewState.Idle) return

    // Centered popup dialog. onDismissRequest fires on outside tap / back —
    // route it through dismissPreview so playback stops and the tmp clip is
    // deleted exactly like the X button.
    androidx.compose.ui.window.Dialog(onDismissRequest = { viewModel.dismissPreview() }) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
            shadowElevation = 16.dp,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            when (val p = preview) {
                is PreviewState.Loading -> {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Fetching preview…",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                p.result.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { viewModel.dismissPreview() }) {
                            Icon(Icons.Default.Close, "Dismiss",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                is PreviewState.Error -> {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.LibraryMusic, null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Preview failed",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                p.message,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { viewModel.dismissPreview() }) {
                            Icon(Icons.Default.Close, "Dismiss",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                is PreviewState.Ready -> {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Thumbnail (48dp, rounded) with neutral fallback.
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surface),
                                contentAlignment = Alignment.Center
                            ) {
                                if (p.result.thumbnailUrl != null) {
                                    AsyncImage(
                                        model = p.result.thumbnailUrl,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(Icons.Default.LibraryMusic, null,
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    p.result.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "30s preview · ${p.result.uploader}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // Play / pause the preview clip.
                            IconButton(onClick = { viewModel.togglePreviewPlayback() }) {
                                Icon(
                                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    if (playing) "Pause" else "Play",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = { viewModel.dismissPreview() }) {
                                Icon(Icons.Default.Close, "Dismiss",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        // Seek slider + elapsed / total. Only enabled once the
                        // player has reported a duration (>0).
                        val durSec = (duration / 1000f).coerceAtLeast(0.001f)
                        val posSec = (position / 1000f).coerceIn(0f, durSec)
                        Slider(
                            value = posSec,
                            onValueChange = { viewModel.seekPreviewTo((it * 1000).toLong()) },
                            valueRange = 0f..durSec,
                            enabled = duration > 0,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp)
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                formatMs(position),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                formatMs(duration),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                PreviewState.Idle -> { /* unreachable — guarded above */ }
            }
        }
    }
}
