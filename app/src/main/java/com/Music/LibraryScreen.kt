package com.Music

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import coil.compose.AsyncImage
import com.Music.data.local.PlaylistEntity
import com.Music.data.local.PlaylistWithSongs
import com.Music.data.local.SongEntity
import com.Music.downloader.PlaylistEntry
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    viewModel: MainViewModel,
    onNavigateToPlayer: () -> Unit,
    onNavigateToPlaylist: (Long) -> Unit,
    // Called when a song is tapped from the list. Opens the big player
    // directly, skipping the mini player so the image only loads once.
    onPlayFromList: () -> Unit = { onNavigateToPlayer() }
) {
    val songs           by viewModel.songs.collectAsState()
    val currentSong     by viewModel.currentSong.collectAsState()
    val isPlaying       by viewModel.isPlaying.collectAsState()
    val isDownloading   by viewModel.isDownloading.collectAsState()
    val activeDownloads by viewModel.activeDownloads.collectAsState()
    val isImporting     by viewModel.isImporting.collectAsState()
    val selectedIds     by viewModel.selectedIds.collectAsState()
    val inSelection     = selectedIds.isNotEmpty()
    val isZipping       by viewModel.isZipping.collectAsState()
    val playlists       by viewModel.playlists.collectAsState()
    val queue           by viewModel.queue.collectAsState()
    val playlistFetch   by viewModel.playlistFetch.collectAsState()
    val batchDownload   by viewModel.batchDownload.collectAsState()

    // Persist the active tab across navigation (e.g. returning from a
    // playlist detail screen lands back on the Playlists tab) and across
    // configuration changes.
    var selectedTab          by rememberSaveable { mutableIntStateOf(0) }
    var showAdd              by remember { mutableStateOf(false) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var showAddSelectedToPlaylist by remember { mutableStateOf(false) }
    val addSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val context = LocalContext.current

    // ── Back-button handling ──────────────────────────────────────────────
    // Priority: open dialogs/sheets → selection mode → search → non-Songs
    // tab → Songs tab (let the system handle back, i.e. exit the app).
    BackHandler(enabled = showAdd) { showAdd = false }
    BackHandler(enabled = showNewPlaylistDialog) { showNewPlaylistDialog = false }
    BackHandler(enabled = showAddSelectedToPlaylist) { showAddSelectedToPlaylist = false }
    BackHandler(enabled = inSelection) { viewModel.clearSelection() }
    BackHandler(enabled = isSearching && selectedTab == 0) {
        isSearching = false
        searchQuery = ""
    }
    // On the Queue or Playlists tab, back returns to the Songs tab instead of
    // leaving the app.
    BackHandler(enabled = !inSelection && !isSearching && selectedTab != 0) {
        selectedTab = 0
    }

    val filteredSongs = remember(songs, searchQuery) {
        if (searchQuery.isEmpty()) songs
        else songs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importLocalSong(it) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.importFromFolder(it) }
    }
    val pickLinksFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importLinksFile(it) }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab != 0) {
            isSearching = false
            searchQuery = ""
        }
    }

    LaunchedEffect(isSearching) {
        if (isSearching) focusRequester.requestFocus()
    }

    // Forward share intents emitted by the ViewModel (e.g. sharing the saved
    // playlist-links file) to the system.
    LaunchedEffect(Unit) {
        viewModel.shareIntents.collect { intent ->
            context.startActivity(intent)
        }
    }

    Scaffold(
        topBar = {
            if (inSelection) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, "Cancel selection")
                        }
                    },
                    title = {
                        Text("${selectedIds.size} selected", fontWeight = FontWeight.SemiBold)
                    },
                    actions = {
                        IconButton(
                            onClick = { showAddSelectedToPlaylist = true },
                            enabled = playlists.isNotEmpty()
                        ) {
                            Icon(Icons.Default.PlaylistAdd, "Add selected to playlist")
                        }
                        IconButton(
                            onClick = { viewModel.shareSelectedAsZip() },
                            enabled = !isZipping
                        ) {
                            if (isZipping) {
                                CircularProgressIndicator(
                                    Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.FolderZip, "Share selected as ZIP")
                            }
                        }
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(Icons.Default.SelectAll, "Select all")
                        }
                        IconButton(onClick = { viewModel.deleteSelected() }) {
                            Icon(
                                Icons.Default.DeleteSweep, "Delete selected",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
            } else if (isSearching && selectedTab == 0) {
                TopAppBar(
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search songs...") },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = MaterialTheme.colorScheme.primary
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { isSearching = false; searchQuery = "" }) {
                            Icon(Icons.Default.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, "Clear")
                            }
                        }
                    }
                )
            } else {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "Library",
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.headlineMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { openYouTube(context) }) {
                            Icon(
                                Icons.Default.SmartDisplay,
                                contentDescription = "Open YouTube",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    actions = {
                        if (selectedTab == 0 && songs.isNotEmpty()) {
                            IconButton(onClick = { isSearching = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                        }
                        IconButton(onClick = { showAdd = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Music")
                        }
                    }
                )
            }
        }
    ) { padding ->
        // When a song is loaded, the morphing player overlay renders a mini
        // bar at the bottom of the screen; reserve space so list content
        // isn't hidden behind it.
        val bottomInset = if (currentSong != null) 84.dp else 0.dp
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    top    = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + bottomInset
                )
        ) {
            // Global Download Meter
            AnimatedVisibility(
                visible = isDownloading || isImporting,
                enter   = expandVertically() + fadeIn(),
                exit    = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    color    = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                    modifier = Modifier.fillMaxWidth().shadow(4.dp)
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        if (isImporting) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                                Icon(Icons.Default.UploadFile, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("Importing songs...", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            }
                        }
                        
                        activeDownloads.values.forEach { task ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Download, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text       = "${task.title ?: "Fetching info..."} • ${task.progress.toInt()}%",
                                        style      = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines   = 1,
                                        overflow   = TextOverflow.Ellipsis,
                                        modifier   = Modifier.weight(1f)
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress   = { task.progress / 100f },
                                    modifier   = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    color      = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                )
                            }
                        }
                    }
                }
            }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Songs") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Queue") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Playlists") })
            }

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { dir * it / 3 })
                        .togetherWith(fadeOut(tween(130)) + slideOutHorizontally(tween(180)) { -dir * it / 3 })
                },
                label = "tabs"
            ) { tab ->
                when (tab) {
                    0 -> SongsTab(
                        songs           = filteredSongs,
                        currentSong     = currentSong,
                        isPlaying       = isPlaying,
                        selectedIds     = selectedIds,
                        inSelection     = inSelection,
                        playlists       = playlists.map { it.playlist },
                        queue           = queue,
                        onPlay          = { song ->
                            if (song.id != currentSong?.id) {
                                viewModel.playSong(song)
                            }
                            // Open the big player directly (skip mini player)
                            // so the album art only loads in the big player.
                            onPlayFromList()
                        },
                        onPlayNext      = { song -> viewModel.playNext(song) },
                        onAddToQueue    = { song -> viewModel.addToQueue(song) },
                        onRemoveFromQueue = { id -> viewModel.removeFromQueue(id) },
                        onLongPress     = { id -> viewModel.toggleSelect(id) },
                        onToggleSelect  = { id -> viewModel.toggleSelect(id) },
                        onDelete        = { song -> viewModel.deleteSong(song) },
                        onAddToPlaylist = { songId, plId -> viewModel.addSongToPlaylist(plId, songId) },
                        onStartDrag     = { viewModel.startDrag() },
                        onMove          = { from, to -> viewModel.moveSong(from, to) },
                        onEndDrag       = { viewModel.endDrag() },
                        onOpenAdd       = { showAdd = true },
                        isFiltered      = searchQuery.isNotEmpty()
                    )
                    1 -> QueueTab(
                        queue       = queue,
                        currentSong = currentSong,
                        onPlayItem  = { item ->
                            if (item.mediaId != currentSong?.id) {
                                viewModel.playFromQueue(item)
                            }
                            onNavigateToPlayer()
                        },
                        onRemove    = { item -> viewModel.removeFromQueue(item.mediaId) },
                        onMove      = { from, to -> viewModel.moveQueueItem(from, to) }
                    )
                    2 -> PlaylistsTab(
                        playlists        = playlists,
                        onPlaylistClick  = { pl -> onNavigateToPlaylist(pl.id) },
                        onDeletePlaylist = { pl -> viewModel.deletePlaylist(pl) },
                        onCreatePlaylist = { showNewPlaylistDialog = true }
                    )
                }
            }
        }

        if (showAdd) {
            ModalBottomSheet(
                onDismissRequest = { showAdd = false },
                sheetState = addSheetState,
                contentWindowInsets = { WindowInsets.ime.union(WindowInsets.navigationBars) }
            ) {
                AddMusicSheet(
                    isDownloading    = isDownloading,
                    activeDownloads  = activeDownloads,
                    isImporting      = isImporting,
                    playlistFetch    = playlistFetch,
                    batchDownload    = batchDownload,
                    onDownload       = { url -> viewModel.downloadSong(url); showAdd = false },
                    onPickFile       = {
                        pickFile.launch(arrayOf("audio/*", "video/*"))
                        showAdd = false
                    },
                    onPickFolder     = { pickFolder.launch(null); showAdd = false },
                    onFetchPlaylist      = { url -> viewModel.fetchPlaylistLinks(url) },
                    onClearPlaylistFetch = { viewModel.clearPlaylistFetch() },
                    onSaveLinks          = { viewModel.saveAndShareLinksFile() },
                    onDownloadPlaylist   = { viewModel.downloadPlaylistSongs() },
                    onCancelPlaylist     = { viewModel.cancelPlaylistDownload() },
                    onExportLibrary      = { viewModel.exportLibraryLinks() },
                    onImportLinksFile    = {
                        // Keep the sheet open so the user can review the
                        // imported list and press "Download All".
                        pickLinksFile.launch(arrayOf("text/plain", "text/*", "*/*"))
                    }
                )
            }
        }

        if (showNewPlaylistDialog) {
            NewPlaylistDialog(
                onConfirm = { name -> viewModel.createPlaylist(name); showNewPlaylistDialog = false },
                onDismiss = { showNewPlaylistDialog = false }
            )
        }

        if (showAddSelectedToPlaylist) {
            AddToPlaylistDialog(
                playlists = playlists.map { it.playlist },
                onSelect  = { plId ->
                    viewModel.addSelectedToPlaylist(plId)
                    showAddSelectedToPlaylist = false
                },
                onDismiss = { showAddSelectedToPlaylist = false }
            )
        }
    }
}

// ─── Songs tab ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SongsTab(
    songs: List<SongEntity>,
    currentSong: SongEntity?,
    isPlaying: Boolean,
    selectedIds: Set<String>,
    inSelection: Boolean,
    playlists: List<PlaylistEntity>,
    queue: List<MediaItem>,
    onPlay: (SongEntity) -> Unit,
    onPlayNext: (SongEntity) -> Unit,
    onAddToQueue: (SongEntity) -> Unit,
    onRemoveFromQueue: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onDelete: (SongEntity) -> Unit,
    onAddToPlaylist: (songId: String, playlistId: Long) -> Unit,
    onStartDrag: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onEndDrag: () -> Unit,
    onOpenAdd: () -> Unit,
    isFiltered: Boolean = false
) {
    val context = LocalContext.current
    val haptic  = LocalHapticFeedback.current

    if (songs.isEmpty()) {
        if (isFiltered) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No songs match your search", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            EmptyLibrary(onOpenAdd)
        }
        return
    }

    val lazyListState    = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        onMove(from.index, to.index)
    }

    // ── Drag-to-select ────────────────────────────────────────────────────
    // A long press toggles the anchor song (entering selection mode). Keeping
    // the finger down and dragging across other songs toggles each one as the
    // finger enters it — so dragging back over a marked song deselects it.
    // When the finger lingers near the top or bottom edge the list auto-scrolls
    // so the user can keep marking songs beyond the visible viewport.
    val currentSongs     by rememberUpdatedState(songs)
    val toggleSelectCb   by rememberUpdatedState(onToggleSelect)
    var dragSelectActive by remember { mutableStateOf(false) }
    var lastDragIndex    by remember { mutableStateOf(-1) }
    // Latest finger Y (in list-local px) while dragging; -1 when idle.
    var dragY by remember { mutableFloatStateOf(-1f) }

    fun itemInfoAt(y: Float) =
        lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            y >= info.offset && y < info.offset + info.size
        }

    // Auto-scroll loop: while a drag is active, scroll up/down when the finger
    // is within the edge zone. Runs on the default dispatcher via LaunchedEffect.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val edgeZonePx = with(density) { 64.dp.toPx() }
    val scrollSpeedPx = with(density) { 12.dp.toPx() } // px per tick
    LaunchedEffect(dragSelectActive) {
        if (!dragSelectActive) return@LaunchedEffect
        while (dragSelectActive) {
            val y = dragY
            if (y >= 0f) {
                val viewport = lazyListState.layoutInfo.viewportSize.height
                if (y < edgeZonePx) {
                    // Near the top — scroll up.
                    lazyListState.scrollBy(-scrollSpeedPx)
                } else if (y > viewport - edgeZonePx) {
                    // Near the bottom — scroll down.
                    lazyListState.scrollBy(scrollSpeedPx)
                }
            }
            kotlinx.coroutines.delay(16)
        }
    }

    LazyColumn(
        state    = lazyListState,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val info = itemInfoAt(offset.y) ?: return@detectDragGesturesAfterLongPress
                        val id   = currentSongs.getOrNull(info.index)?.id ?: return@detectDragGesturesAfterLongPress
                        dragSelectActive = true
                        lastDragIndex    = info.index
                        dragY            = offset.y
                        toggleSelectCb(id)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { change, _ ->
                        if (!dragSelectActive) return@detectDragGesturesAfterLongPress
                        dragY = change.position.y
                        val info = itemInfoAt(change.position.y) ?: return@detectDragGesturesAfterLongPress
                        if (info.index != lastDragIndex) {
                            val id = currentSongs.getOrNull(info.index)?.id
                            if (id != null) toggleSelectCb(id)
                            lastDragIndex = info.index
                        }
                        change.consume()
                    },
                    onDragEnd    = { dragSelectActive = false; lastDragIndex = -1; dragY = -1f },
                    onDragCancel = { dragSelectActive = false; lastDragIndex = -1; dragY = -1f }
                )
            },
        contentPadding = PaddingValues(bottom = 8.dp)
    ) {
        items(songs, key = { it.id }) { song ->
            ReorderableItem(reorderableState, key = song.id) { isDragging ->
                val isInQueue = queue.any { it.mediaId == song.id }
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = {
                        if (it == SwipeToDismissBoxValue.StartToEnd) {
                            onAddToQueue(song)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            false
                        } else false
                    }
                )

                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = !inSelection,
                    enableDismissFromEndToStart = false,
                    backgroundContent = {
                        val direction = dismissState.dismissDirection
                        if (direction == SwipeToDismissBoxValue.StartToEnd && !inSelection) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(horizontal = 20.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Icon(
                                    Icons.Default.Queue,
                                    null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                ) {
                    SongListItem(
                        song               = song,
                        isCurrent          = song.id == currentSong?.id,
                        isPlaying          = isPlaying && song.id == currentSong?.id,
                        isInQueue          = isInQueue,
                        isSelected         = song.id in selectedIds,
                        inSelection        = inSelection,
                        isDragging         = isDragging,
                        dragHandleModifier = Modifier.draggableHandle(
                            enabled       = !inSelection && !isFiltered,
                            onDragStarted = { onStartDrag() },
                            onDragStopped = { onEndDrag() }
                        ),
                        playlists          = playlists,
                        onPlay             = { onPlay(song) },
                        onPlayNext         = { onPlayNext(song) },
                        onAddToQueue       = { onAddToQueue(song) },
                        onRemoveFromQueue  = { onRemoveFromQueue(song.id) },
                        onLongPress        = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress(song.id)
                        },
                        onToggleSelect     = { onToggleSelect(song.id) },
                        onDelete           = { onDelete(song) },
                        onShare            = { shareSong(context, song) },
                        onAddToPlaylist    = { plId -> onAddToPlaylist(song.id, plId) }
                    )
                }
            }
        }
    }
}

private fun shareSong(context: Context, song: SongEntity) {
    val file = File(song.filePath)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = if (song.filePath.substringAfterLast(".").lowercase() in setOf("mp4", "mkv", "webm")) "video/*" else "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, song.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share \"${song.title}\""
        )
    )
}

// ─── Song row ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongListItem(
    song: SongEntity,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isInQueue: Boolean,
    isSelected: Boolean,
    inSelection: Boolean,
    isDragging: Boolean,
    dragHandleModifier: Modifier = Modifier,
    playlists: List<PlaylistEntity> = emptyList(),
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onRemoveFromQueue: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onAddToPlaylist: (Long) -> Unit
) {
    var showMenu          by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }

    // In selection mode the blue highlight follows the selection (not the
    // currently-playing song); outside selection it marks the current song.
    val bgAlpha   by animateFloatAsState(
        if (inSelection) { if (isSelected) 0.18f else 0f } else { if (isCurrent) 0.18f else 0f },
        label = "songBg"
    )
    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "dragElev")

    ListItem(
        modifier = Modifier
            .shadow(elevation, RoundedCornerShape(12.dp))
            .background(
                if (isDragging) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.primaryContainer.copy(alpha = bgAlpha)
            )
            .combinedClickable(
                onClick = { if (inSelection) onToggleSelect() else onPlay() }
            ),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    song.title,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    fontWeight = if (!inSelection && isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color      = if (!inSelection && isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isInQueue && !isCurrent && isDragging) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.Queue, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                }
            }
        },
        supportingContent = {
            Text(
                song.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            if (inSelection) {
                Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
            } else {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (song.thumbnailUrl != null) {
                        AsyncImage(
                            model              = song.thumbnailUrl,
                            contentDescription = null,
                            modifier           = Modifier.fillMaxSize(),
                            contentScale       = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Default.MusicNote, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isCurrent) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                null,
                                tint     = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        },
        trailingContent = {
            if (!inSelection) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                Icons.Default.MoreVert, "More options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded         = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text        = { Text("Select Multiple") },
                                leadingIcon = { Icon(Icons.Default.Checklist, null) },
                                onClick     = { showMenu = false; onLongPress() }
                            )
                            DropdownMenuItem(
                                text        = { Text("Play Next") },
                                leadingIcon = { Icon(Icons.Default.SkipNext, null) },
                                onClick     = { showMenu = false; onPlayNext() }
                            )
                            if (isInQueue) {
                                DropdownMenuItem(
                                    text        = { Text("Remove from Queue") },
                                    leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, null) },
                                    onClick     = { showMenu = false; onRemoveFromQueue() }
                                )
                            } else {
                                DropdownMenuItem(
                                    text        = { Text("Add to Queue") },
                                    leadingIcon = { Icon(Icons.Default.Queue, null) },
                                    onClick     = { showMenu = false; onAddToQueue() }
                                )
                            }
                            DropdownMenuItem(
                                text        = { Text("Add to Playlist") },
                                leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) },
                                onClick     = { showMenu = false; showAddToPlaylist = true }
                            )
                            DropdownMenuItem(
                                text        = { Text("Share") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick     = { showMenu = false; onShare() }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text        = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, null,
                                        tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = { showMenu = false; onDelete() }
                            )
                        }
                    }
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "Drag to reorder",
                        modifier           = dragHandleModifier.size(20.dp),
                        tint               = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                    Spacer(Modifier.width(4.dp))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
    HorizontalDivider(
        Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    )

    if (showAddToPlaylist) {
        AddToPlaylistDialog(
            playlists = playlists,
            onSelect  = { plId -> onAddToPlaylist(plId); showAddToPlaylist = false },
            onDismiss = { showAddToPlaylist = false }
        )
    }
}

// ─── Playlists tab ────────────────────────────────────────────────────────────

@Composable
private fun PlaylistsTab(
    playlists: List<PlaylistWithSongs>,
    onPlaylistClick: (PlaylistEntity) -> Unit,
    onDeletePlaylist: (PlaylistEntity) -> Unit,
    onCreatePlaylist: () -> Unit
) {
    if (playlists.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.QueueMusic, null, Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                Text("No playlists yet", style = MaterialTheme.typography.titleMedium)
                FilledTonalButton(onClick = onCreatePlaylist) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New Playlist")
                }
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            TextButton(
                onClick  = onCreatePlaylist,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("New Playlist")
            }
        }
        items(playlists, key = { it.playlist.id }) { pw ->
            PlaylistItem(
                pw,
                onClick  = { onPlaylistClick(pw.playlist) },
                onDelete = { onDeletePlaylist(pw.playlist) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistItem(
    playlistWithSongs: PlaylistWithSongs,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showConfirm by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = {}),
        headlineContent = {
            Text(playlistWithSongs.playlist.name, fontWeight = FontWeight.SemiBold)
        },
        supportingContent = {
            Text(
                "${playlistWithSongs.songs.size} songs",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.QueueMusic, null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        },
        trailingContent = {
            IconButton(onClick = { showConfirm = true }) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
            }
        }
    )
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title   = { Text("Delete playlist?") },
            text    = {
                Text("\"${playlistWithSongs.playlist.name}\" will be removed. Songs stay in library.")
            },
            confirmButton = {
                TextButton(onClick = { onDelete(); showConfirm = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

// ─── Queue tab ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun QueueTab(
    queue: List<MediaItem>,
    currentSong: SongEntity?,
    onPlayItem: (MediaItem) -> Unit,
    onRemove: (MediaItem) -> Unit,
    onMove: (Int, Int) -> Unit
) {
    if (queue.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Queue is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        onMove(from.index, to.index)
    }

    LazyColumn(state = lazyListState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(queue, key = { _, item -> item.mediaId }) { index, item ->
            ReorderableItem(reorderableState, key = item.mediaId) { isDragging ->
                val isCurrent = item.mediaId == currentSong?.id
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = {
                        if (it == SwipeToDismissBoxValue.EndToStart) {
                            onRemove(item)
                            true
                        } else false
                    }
                )

                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) {
                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                ) {
                    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "queueDragElev")
                    ListItem(
                        modifier = Modifier
                            .shadow(elevation, RoundedCornerShape(12.dp))
                            .background(if (isDragging) MaterialTheme.colorScheme.surface else Color.Transparent)
                            .clickable { onPlayItem(item) },
                        headlineContent = {
                            Text(item.mediaMetadata.title?.toString() ?: "Unknown",
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        },
                        supportingContent = { Text(item.mediaMetadata.artist?.toString() ?: "Unknown") },
                        leadingContent = {
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                if (item.mediaMetadata.artworkUri != null) {
                                    AsyncImage(model = item.mediaMetadata.artworkUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else {
                                    Icon(Icons.Default.MusicNote, null, modifier = Modifier.size(20.dp))
                                }
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { onRemove(item) }) {
                                    Icon(Icons.Default.RemoveCircleOutline, "Remove", tint = MaterialTheme.colorScheme.error)
                                }
                                Icon(
                                    Icons.Default.DragHandle,
                                    null,
                                    modifier = Modifier.draggableHandle().size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                )
                            }
                        }
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
            }
        }
    }
}

// ─── Dialogs ──────────────────────────────────────────────────────────────────

@Composable
fun NewPlaylistDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("New Playlist") },
        text    = {
            OutlinedTextField(
                value         = name,
                onValueChange = { name = it },
                label         = { Text("Playlist name") },
                singleLine    = true,
                shape         = RoundedCornerShape(12.dp)
            )
        },
        confirmButton = {
            TextButton(
                onClick  = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled  = name.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun AddToPlaylistDialog(
    playlists: List<PlaylistEntity>,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("Add to Playlist") },
        text    = {
            if (playlists.isEmpty()) {
                Text("No playlists yet. Create one in the Playlists tab first.")
            } else {
                LazyColumn {
                    itemsIndexed(playlists) { _, pl ->
                        TextButton(
                            onClick  = { onSelect(pl.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.QueueMusic, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(pl.name, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ─── Mini player ──────────────────────────────────────────────────────────────

@Composable
fun MiniPlayer(
    song: SongEntity,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onTap: () -> Unit,
    onDragUp: ((Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onDragCancel: (() -> Unit)? = null,
    // When non-null, the thumbnail reports its on-screen bounds here and is
    // hidden (the hero image in PlayerOverlay draws on top during the morph).
    onThumbnailPositioned: ((androidx.compose.ui.unit.IntRect) -> Unit)? = null,
    hideThumbnail: Boolean = false
) {
    // Use a small custom touch slop so the drag engages quickly without the
    // user having to hold/move a lot before the player starts following.
    val touchSlop = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }
    val dragModifier = if (onDragUp != null && onDragEnd != null && onDragCancel != null) {
        // verticalDrag uses the Initial pointer pass so it can intercept an
        // upward drag without blocking taps on the play/pause/skip buttons.
        Modifier.verticalDrag(
            touchSlop    = touchSlop,
            onDrag       = onDragUp,
            onDragEnd    = onDragEnd,
            onDragCancel = onDragCancel
        ).combinedClickable(onClick = onTap, onLongClick = {})
    } else {
        Modifier.combinedClickable(onClick = onTap, onLongClick = {})
    }

    Card(
        modifier  = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .then(dragModifier),
        shape     = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(8.dp),
        colors    = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .then(
                        if (onThumbnailPositioned != null) {
                            Modifier.onGloballyPositioned { coords ->
                                val pos = coords.positionInRoot()
                                val size = coords.size
                                onThumbnailPositioned(
                                    androidx.compose.ui.unit.IntRect(
                                        left   = pos.x.toInt(),
                                        top    = pos.y.toInt(),
                                        right  = (pos.x + size.width).toInt(),
                                        bottom = (pos.y + size.height).toInt()
                                    )
                                )
                            }
                        } else Modifier
                    )
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (!hideThumbnail) {
                    if (song.thumbnailUrl != null) {
                        AsyncImage(
                            model              = song.thumbnailUrl,
                            contentDescription = null,
                            modifier           = Modifier.fillMaxSize(),
                            contentScale       = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Default.MusicNote, null)
                    }
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    song.title,
                    style      = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Text(
                    song.artist,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Default.SkipPrevious, "Previous")
                }
                IconButton(onClick = onToggle) {
                    AnimatedContent(
                        targetState = isPlaying,
                        transitionSpec = {
                            (scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn())
                                .togetherWith(scaleOut() + fadeOut())
                        },
                        label = "miniPP"
                    ) { playing ->
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (playing) "Pause" else "Play",
                            Modifier.size(28.dp)
                        )
                    }
                }
                IconButton(onClick = { onNext() }) {
                    Icon(Icons.Default.SkipNext, "Next")
                }
            }
        }
    }
}

// ─── Empty state ──────────────────────────────────────────────────────────────

@Composable
fun EmptyLibrary(onAdd: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.LibraryMusic, null, Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
            Text(
                "Library is empty",
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Add songs to get started",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onAdd) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add Music")
            }
        }
    }
}

// ─── Add music sheet ──────────────────────────────────────────────────────────

@Composable
fun AddMusicSheet(
    isDownloading: Boolean,
    activeDownloads: Map<String, DownloadTask>,
    isImporting: Boolean,
    playlistFetch: PlaylistFetchState,
    batchDownload: BatchDownloadState,
    onDownload: (String) -> Unit,
    onPickFile: () -> Unit,
    onPickFolder: () -> Unit,
    onFetchPlaylist: (String) -> Unit,
    onClearPlaylistFetch: () -> Unit,
    onSaveLinks: () -> Unit,
    onDownloadPlaylist: () -> Unit,
    onCancelPlaylist: () -> Unit,
    onExportLibrary: () -> Unit,
    onImportLinksFile: () -> Unit
) {
    var tab     by remember { mutableIntStateOf(0) }
    var urlText by remember { mutableStateOf("") }
    var playlistUrlText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .imePadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Add Music",
            style      = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp)
        )
        Spacer(Modifier.height(16.dp))
        TabRow(selectedTabIndex = tab) {
            listOf("Link", "List", "File", "Folder").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 180.dp)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val d = if (targetState > initialState) 1 else -1
                    (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { d * it / 3 })
                        .togetherWith(fadeOut(tween(130)) + slideOutHorizontally(tween(180)) { -d * it / 3 })
                },
                label = "addTab"
            ) { t ->
                when (t) {
                    0 -> Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value         = urlText,
                            onValueChange = { urlText = it },
                            modifier      = Modifier.fillMaxWidth(),
                            placeholder   = { Text("Paste links (one per line or comma separated)") },
                            minLines      = 3,
                            maxLines      = 10,
                            shape         = RoundedCornerShape(12.dp),
                            leadingIcon   = { Icon(Icons.Default.Link, null) }
                        )
                        Button(
                            onClick  = { onDownload(urlText); urlText = "" },
                            enabled  = urlText.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape    = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Download, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (activeDownloads.isNotEmpty()) "Add More to Download" else "Download")
                        }
                        
                        if (activeDownloads.isNotEmpty()) {
                            Text(
                                "Active Downloads (${activeDownloads.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            activeDownloads.values.forEach { task ->
                                Column(Modifier.padding(vertical = 4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "${task.title ?: "Fetching info..."} • ${task.progress.toInt()}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { task.progress / 100f },
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                                    )
                                }
                            }
                        }
                    }

                    1 -> PlaylistImportTab(
                        playlistUrlText      = playlistUrlText,
                        onPlaylistUrlChange  = { playlistUrlText = it },
                        playlistFetch        = playlistFetch,
                        batchDownload        = batchDownload,
                        onFetchPlaylist      = { onFetchPlaylist(playlistUrlText) },
                        onClearPlaylistFetch = {
                            onClearPlaylistFetch()
                            playlistUrlText = ""
                        },
                        onSaveLinks          = onSaveLinks,
                        onDownloadPlaylist   = onDownloadPlaylist,
                        onCancelPlaylist     = onCancelPlaylist,
                        onExportLibrary      = onExportLibrary,
                        onImportLinksFile    = onImportLinksFile
                    )

                    2 -> Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.AudioFile, null, Modifier.size(52.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text("Pick audio or video files from your device",
                            textAlign = TextAlign.Center)
                        Text(
                            "Supports MP3, FLAC, M4A, OGG, WAV, MP4 and more",
                            style     = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color     = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick  = onPickFile,
                            enabled  = !isImporting,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape    = RoundedCornerShape(12.dp)
                        ) {
                            if (isImporting) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp),
                                    color       = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp)); Text("Importing...")
                            } else {
                                Icon(Icons.Default.FolderOpen, null)
                                Spacer(Modifier.width(8.dp)); Text("Browse Files")
                            }
                        }
                    }

                    else -> Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.Folder, null, Modifier.size(52.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text("Import all audio from a folder", textAlign = TextAlign.Center)
                        Text(
                            "Every audio file in the folder gets added to your library",
                            style     = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color     = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick  = onPickFolder,
                            enabled  = !isImporting,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape    = RoundedCornerShape(12.dp)
                        ) {
                            if (isImporting) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp),
                                    color       = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp)); Text("Scanning folder...")
                            } else {
                                Icon(Icons.Default.CreateNewFolder, null)
                                Spacer(Modifier.width(8.dp)); Text("Choose Folder")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Playlist import tab ──────────────────────────────────────────────────────

@Composable
private fun PlaylistImportTab(
    playlistUrlText: String,
    onPlaylistUrlChange: (String) -> Unit,
    playlistFetch: PlaylistFetchState,
    batchDownload: BatchDownloadState,
    onFetchPlaylist: () -> Unit,
    onClearPlaylistFetch: () -> Unit,
    onSaveLinks: () -> Unit,
    onDownloadPlaylist: () -> Unit,
    onCancelPlaylist: () -> Unit,
    onExportLibrary: () -> Unit,
    onImportLinksFile: () -> Unit
) {
    val entries = playlistFetch.entries
    val hasEntries = entries.isNotEmpty()
    val batchRunning = batchDownload.isRunning

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Transfer library between phones ────────────────────────────────
        Surface(
            color    = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape    = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Transfer library",
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Export every downloaded song's link to a text file, or import a links file from another phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick  = onExportLibrary,
                        enabled  = !batchRunning,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape    = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.UploadFile, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Export")
                    }
                    Button(
                        onClick  = onImportLinksFile,
                        enabled  = !batchRunning,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape    = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.FileOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Import")
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
        Text(
            "From a YouTube playlist",
            style      = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        OutlinedTextField(
            value         = playlistUrlText,
            onValueChange = onPlaylistUrlChange,
            modifier      = Modifier.fillMaxWidth(),
            placeholder   = { Text("Paste YouTube playlist URL") },
            singleLine    = true,
            shape         = RoundedCornerShape(12.dp),
            leadingIcon   = { Icon(Icons.Default.PlaylistPlay, null) },
            trailingIcon  = {
                if (playlistUrlText.isNotEmpty()) {
                    IconButton(onClick = onClearPlaylistFetch) {
                        Icon(Icons.Default.Clear, "Clear")
                    }
                }
            }
        )

        Button(
            onClick  = onFetchPlaylist,
            enabled  = playlistUrlText.isNotBlank() && !playlistFetch.isLoading && !batchRunning,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape    = RoundedCornerShape(12.dp)
        ) {
            if (playlistFetch.isLoading) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    color       = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp)); Text("Fetching playlist...")
            } else {
                Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(8.dp)); Text("Fetch Playlist")
            }
        }

        playlistFetch.error?.let { err ->
            Text(
                err,
                color  = MaterialTheme.colorScheme.error,
                style  = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (hasEntries) {
            Text(
                "${entries.size} songs found",
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.padding(top = 4.dp)
            )
            // Preview the first few entries
            entries.take(10).forEach { entry ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${entry.index}.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(32.dp)
                    )
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (entries.size > 10) {
                Text(
                    "+${entries.size - 10} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 32.dp)
                )
            }

            // Action buttons
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick  = onSaveLinks,
                    enabled  = !batchRunning,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FileDownload, null)
                    Spacer(Modifier.width(8.dp)); Text("Save Links")
                }
                Button(
                    onClick  = onDownloadPlaylist,
                    enabled  = !batchRunning,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.width(8.dp)); Text("Download All")
                }
            }

            // Batch progress + cancel
            if (batchRunning) {
                Surface(
                    color    = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    shape    = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (batchDownload.isCancelling) "Cancelling..."
                                else "${batchDownload.completed}/${batchDownload.total}",
                                style      = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier   = Modifier.weight(1f)
                            )
                            if (!batchDownload.isCancelling) {
                                TextButton(
                                    onClick = onCancelPlaylist,
                                    colors  = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(Icons.Default.Cancel, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp)); Text("Cancel")
                                }
                            }
                        }
                        batchDownload.currentTitle?.let { title ->
                            Text(
                                title,
                                style    = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        val overall = if (batchDownload.total > 0)
                            (batchDownload.completed + batchDownload.currentProgress / 100f) / batchDownload.total
                        else 0f
                        LinearProgressIndicator(
                            progress = { overall.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
        }
    }
}