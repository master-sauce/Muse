package com.Music

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.Music.data.local.SongEntity
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onNavigateToPlayer: () -> Unit
) {
    val songs           by viewModel.playlistSongs.collectAsState()
    // Each playlist keeps its own sort mode now, so we read the per-id map and
    // look up this playlist's mode (defaulting to NEWEST). Collecting the map
    // keeps the UI in sync if the mode changes elsewhere.
    val playlistSortModes by viewModel.playlistSortModes.collectAsState()
    val playlistSortMode = playlistSortModes[playlistId] ?: SongSortMode.NEWEST
    val playlists       by viewModel.playlists.collectAsState()
    val playlist        = playlists.find { it.playlist.id == playlistId }?.playlist
    val currentSong     by viewModel.currentSong.collectAsState()
    val isPlaying       by viewModel.isPlaying.collectAsState()
    val queue           by viewModel.queue.collectAsState()
    val selectedIds     by viewModel.playlistSelectedIds.collectAsState()
    val inSelection     = selectedIds.isNotEmpty()
    val isZipping       by viewModel.isZipping.collectAsState()
    var showAddSelectedToPlaylist by remember { mutableStateOf(false) }
    var showShareMethodDialog     by remember { mutableStateOf(false) }
    // The chooser that pops up when the user taps the trash icon while songs
    // are selected: offers "Remove from Playlist" (keeps the songs in the
    // library) vs "Delete" (permanently removes the files from the library).
    // Each choice opens its own confirm dialog below.
    var showManageSelectedDialog  by remember { mutableStateOf(false) }
    var showConfirmRemoveSelected by remember { mutableStateOf(false) }
    var showConfirmDeleteSelected by remember { mutableStateOf(false) }
    // Confirm-before-remove dialog for removing a single song via its row
    // menu. Remembers which song the user tapped so the dialog can name it
    // and the confirm handler knows what to remove.
    var showConfirmRemoveSingle  by remember { mutableStateOf(false) }
    var pendingRemoveSong        by remember { mutableStateOf<SongEntity?>(null) }
    val canReorder      = playlistSortMode == SongSortMode.CUSTOM

    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val filteredSongs = remember(songs, searchQuery) {
        if (searchQuery.isEmpty()) songs
        else songs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true)
        }
    }

    val haptic = LocalHapticFeedback.current

    // Clear the playlist selection whenever we leave this screen.
    LaunchedEffect(playlistId) {
        viewModel.loadPlaylistSongs(playlistId)
    }
    DisposableEffect(playlistId) {
        onDispose { viewModel.clearPlaylistSelection() }
    }

    // ── Back-button handling ──────────────────────────────────────────────
    // In selection mode, back clears the selection instead of leaving the
    // screen. The "add selected to playlist" dialog is handled next.
    BackHandler(enabled = showAddSelectedToPlaylist) { showAddSelectedToPlaylist = false }
    BackHandler(enabled = showShareMethodDialog) { showShareMethodDialog = false }
    BackHandler(enabled = showManageSelectedDialog) { showManageSelectedDialog = false }
    BackHandler(enabled = showConfirmRemoveSelected) { showConfirmRemoveSelected = false }
    BackHandler(enabled = showConfirmDeleteSelected) { showConfirmDeleteSelected = false }
    BackHandler(enabled = inSelection) { viewModel.clearPlaylistSelection() }
    BackHandler(enabled = isSearching && !inSelection) {
        isSearching = false
        searchQuery = ""
    }

    LaunchedEffect(isSearching) {
        if (isSearching) focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            if (inSelection) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearPlaylistSelection() }) {
                            Icon(Icons.Default.Close, "Cancel selection")
                        }
                    },
                    title = {
                        Text("${selectedIds.size} selected", fontWeight = FontWeight.SemiBold)
                    },
                    actions = {
                        IconButton(
                            onClick = { showAddSelectedToPlaylist = true },
                            enabled = playlists.any { it.playlist.id != playlistId }
                        ) {
                            Icon(Icons.Default.PlaylistAdd, "Add selected to playlist")
                        }
                        IconButton(
                            onClick = { showShareMethodDialog = true },
                            enabled = !isZipping
                        ) {
                            if (isZipping) {
                                CircularProgressIndicator(
                                    Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Share, "Share selected")
                            }
                        }
                        IconButton(onClick = { viewModel.selectAllPlaylist() }) {
                            Icon(Icons.Default.SelectAll, "Select all")
                        }
                        IconButton(onClick = { showManageSelectedDialog = true }) {
                            Icon(
                                Icons.Default.DeleteSweep, "Manage selected songs",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
            } else if (isSearching) {
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
                TopAppBar(
                    title = {
                        Column {
                            Text(playlist?.name ?: "Playlist", fontWeight = FontWeight.Bold)
                            Text("${songs.size} songs",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                    },
                    actions = {
                        if (songs.isNotEmpty()) {
                            // Sort menu — Newest / Oldest / Custom. Drag-reorder
                            // only works in Custom, so the menu doubles as the
                            // way to re-enable dragging.
                            var showSortMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(Icons.Default.Sort, contentDescription = "Sort")
                                }
                                DropdownMenu(
                                    expanded         = showSortMenu,
                                    onDismissRequest = { showSortMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text        = { Text("Newest first") },
                                        leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                                        trailingIcon = {
                                            if (playlistSortMode == SongSortMode.NEWEST) {
                                                Icon(Icons.Default.Check, null,
                                                    tint = MaterialTheme.colorScheme.primary)
                                            }
                                        },
                                        onClick = {
                                            showSortMenu = false
                                            viewModel.setPlaylistSortMode(playlistId, SongSortMode.NEWEST)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text        = { Text("Oldest first") },
                                        leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                                        trailingIcon = {
                                            if (playlistSortMode == SongSortMode.OLDEST) {
                                                Icon(Icons.Default.Check, null,
                                                    tint = MaterialTheme.colorScheme.primary)
                                            }
                                        },
                                        onClick = {
                                            showSortMenu = false
                                            viewModel.setPlaylistSortMode(playlistId, SongSortMode.OLDEST)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text        = { Text("Custom order") },
                                        leadingIcon = { Icon(Icons.Default.DragHandle, null) },
                                        trailingIcon = {
                                            if (playlistSortMode == SongSortMode.CUSTOM) {
                                                Icon(Icons.Default.Check, null,
                                                    tint = MaterialTheme.colorScheme.primary)
                                            }
                                        },
                                        onClick = {
                                            showSortMenu = false
                                            viewModel.setPlaylistSortMode(playlistId, SongSortMode.CUSTOM)
                                        }
                                    )
                                }
                            }
                            IconButton(onClick = { isSearching = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        // The morphing player overlay renders the mini bar at the bottom of
        // the screen; reserve space so list content isn't hidden behind it.
        val bottomInset = if (currentSong != null) 84.dp else 0.dp
        val contentPadding = PaddingValues(
            top    = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + bottomInset
        )
        if (filteredSongs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.MusicNote, null, Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                    Spacer(Modifier.height(8.dp))
                    if (searchQuery.isNotEmpty()) {
                        Text("No songs match your search",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("No songs in this playlist",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Add songs via the ⋮ menu in the library",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }
            }
        } else {
            val lazyListState = rememberLazyListState()
            val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
                viewModel.movePlaylistSong(playlistId, from.index - 1, to.index - 1)
            }

            // ── Drag-to-select ────────────────────────────────────────────────
            // Same gesture as the library: long press toggles the anchor song,
            // then dragging across other songs toggles each one as the finger
            // enters it — so dragging back over a marked song deselects it.
            // When the finger lingers near the top or bottom edge the list
            // auto-scrolls so the user can keep marking songs beyond the
            // visible viewport; songs scrolling under a stationary finger are
            // toggled too, so none are missed.
            val currentSongs     by rememberUpdatedState(filteredSongs)
            val toggleSelectCb   by rememberUpdatedState { id: String -> viewModel.togglePlaylistSelect(id) }
            var dragSelectActive by remember { mutableStateOf(false) }
            var lastDragIndex    by remember { mutableStateOf(-1) }
            // Latest finger Y (in list-local px) while dragging; -1 when idle.
            var dragY by remember { mutableFloatStateOf(-1f) }

            fun itemInfoAt(y: Float) =
                lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                    y >= info.offset && y < info.offset + info.size
                }

            // Toggle the song currently under the finger (if it's a new one).
            // Shared by the drag handler and the auto-scroll loop so scrolling
            // never skips songs. The first list item is the Play All / Shuffle
            // header, so song rows start at index 1.
            fun toggleItemAt(y: Float) {
                val info = itemInfoAt(y) ?: return
                val songIndex = info.index - 1
                if (songIndex < 0) return
                if (songIndex == lastDragIndex) return
                val id = currentSongs.getOrNull(songIndex)?.id ?: return
                toggleSelectCb(id)
                lastDragIndex = songIndex
            }

            // Auto-scroll loop: while a drag is active, scroll up/down when the
            // finger is within the edge zone, and toggle whatever song ends up
            // under the finger after each scroll step.
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
                            lazyListState.scrollBy(-scrollSpeedPx)
                            toggleItemAt(y)
                        } else if (y > viewport - edgeZonePx) {
                            lazyListState.scrollBy(scrollSpeedPx)
                            toggleItemAt(y)
                        }
                    }
                    kotlinx.coroutines.delay(16)
                }
            }

            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                val info = itemInfoAt(offset.y) ?: return@detectDragGesturesAfterLongPress
                                // The first list item is the Play All / Shuffle
                                // header, so song rows start at index 1.
                                val songIndex = info.index - 1
                                val id = currentSongs.getOrNull(songIndex)?.id ?: return@detectDragGesturesAfterLongPress
                                dragSelectActive = true
                                lastDragIndex    = songIndex
                                dragY            = offset.y
                                toggleSelectCb(id)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { change, _ ->
                                if (!dragSelectActive) return@detectDragGesturesAfterLongPress
                                dragY = change.position.y
                                toggleItemAt(change.position.y)
                                change.consume()
                            },
                            onDragEnd    = { dragSelectActive = false; lastDragIndex = -1; dragY = -1f },
                            onDragCancel = { dragSelectActive = false; lastDragIndex = -1; dragY = -1f }
                        )
                    },
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick  = { viewModel.playSongList(filteredSongs, 0, fromPlaylistId = playlistId) },
                            modifier = Modifier.weight(1f),
                            shape    = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play All")
                        }
                        OutlinedButton(
                            onClick  = {
                                viewModel.toggleShuffle()
                                viewModel.playSongList(filteredSongs, 0, fromPlaylistId = playlistId)
                            },
                            modifier = Modifier.weight(1f),
                            shape    = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Shuffle")
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }

                itemsIndexed(filteredSongs, key = { _, s -> s.id }) { index, song ->
                    ReorderableItem(reorderableState, key = song.id) { isDragging ->
                        val isInQueue = queue.any { it.mediaId == song.id && it.mediaId != currentSong?.id }
                        
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = {
                                if (it == SwipeToDismissBoxValue.StartToEnd && !inSelection) {
                                    viewModel.addToQueue(song)
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
                                        Icon(Icons.Default.Queue, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                            }
                        ) {
                            val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "plDragElev")
                            PlaylistSongItem(
                                song        = song,
                                isCurrent   = song.id == currentSong?.id,
                                isPlaying   = isPlaying && song.id == currentSong?.id,
                                isInQueue   = isInQueue,
                                isSelected  = song.id in selectedIds,
                                inSelection = inSelection,
                                isDragging  = isDragging,
                                dragHandleModifier = Modifier.draggableHandle(
                                    enabled       = canReorder && !inSelection && searchQuery.isEmpty(),
                                    onDragStarted = { viewModel.startDrag() },
                                    onDragStopped = { viewModel.endPlaylistDrag(playlistId) }
                                ),
                                elevation = elevation,
                                onPlay    = {
                                    if (song.id != currentSong?.id) {
                                        // Match the library behaviour: play the
                                        // tapped song within the FULL playlist
                                        // (not the search-filtered list), so
                                        // next/previous navigates every song in
                                        // the playlist even while searching.
                                        val fullIndex = songs.indexOfFirst { it.id == song.id }
                                        if (fullIndex >= 0) viewModel.playSongList(songs, fullIndex, fromPlaylistId = playlistId)
                                    }
                                    // Just play from the mini player — don't
                                    // open the big player. The mini player
                                    // appears automatically once a song is
                                    // loaded.
                                },
                                onPlayNext = { viewModel.playNext(song) },
                                onAddToQueue = { viewModel.addToQueue(song) },
                                onRemoveFromQueue = { viewModel.removeFromQueue(song.id) },
                                onToggleSelect = { viewModel.togglePlaylistSelect(song.id) },
                                onShareAsLink = { viewModel.shareSongAsLink(song) },
                                onRemove  = {
                                    pendingRemoveSong = song
                                    showConfirmRemoveSingle = true
                                },
                                onDelete  = { viewModel.deleteSong(song) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddSelectedToPlaylist) {
        AddToPlaylistDialog(
            playlists = playlists.map { it.playlist }.filter { it.id != playlistId },
            onSelect  = { plId ->
                viewModel.addPlaylistSelectedToPlaylist(plId)
                showAddSelectedToPlaylist = false
            },
            onDismiss = { showAddSelectedToPlaylist = false }
        )
    }

    // Same share-method chooser as the Library: Files (zip of the song files)
    // vs Links (the songs' source URLs as text). Shares the playlist-detail
    // selection (not the library's) via the playlist-specific share helpers.
    if (showShareMethodDialog) {
        AlertDialog(
            onDismissRequest = { showShareMethodDialog = false },
            containerColor = MaterialTheme.colorScheme.background,
            title   = { Text("Share selected") },
            text    = {
                Column {
                    Text(
                        "Choose how to share the ${selectedIds.size} selected " +
                        "song${if (selectedIds.size == 1) "" else "s"}."
                    )
                    Spacer(Modifier.height(16.dp))
                    ListItem(
                        modifier = Modifier.clickable {
                            showShareMethodDialog = false
                            viewModel.sharePlaylistSelectedAsZip()
                        },
                        headlineContent = {
                            Text("Files (ZIP)",
                                color = MaterialTheme.colorScheme.primary)
                        },
                        supportingContent = {
                            Text("Bundle the song files into a .zip archive",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        leadingContent = {
                            Icon(Icons.Default.Share, null,
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    )
                    HorizontalDivider()
                    ListItem(
                        modifier = Modifier.clickable {
                            showShareMethodDialog = false
                            viewModel.sharePlaylistSelectedAsLinks()
                        },
                        headlineContent = {
                            Text("Links",
                                color = MaterialTheme.colorScheme.primary)
                        },
                        supportingContent = {
                            Text("Share the song links as text (one per line)",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        leadingContent = {
                            Icon(Icons.Default.Link, null,
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showShareMethodDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Chooser shown when the trash icon is tapped in selection mode. Mirrors
    // the share-method chooser above so the two destructive batch actions
    // (remove from this playlist vs delete from library) are presented the
    // same way. Each row opens its own confirm dialog below.
    if (showManageSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showManageSelectedDialog = false },
            containerColor = MaterialTheme.colorScheme.background,
            title   = { Text("Manage selected") },
            text    = {
                Column {
                    Text(
                        "Choose what to do with the ${selectedIds.size} selected " +
                        "song${if (selectedIds.size == 1) "" else "s"}."
                    )
                    Spacer(Modifier.height(16.dp))
                    ListItem(
                        modifier = Modifier.clickable {
                            showManageSelectedDialog = false
                            showConfirmRemoveSelected = true
                        },
                        headlineContent = {
                            Text("Remove from Playlist",
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.75f))
                        },
                        supportingContent = {
                            Text("Take the songs off this playlist; they stay in your library",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        leadingContent = {
                            Icon(Icons.Default.RemoveCircleOutline, null,
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.75f))
                        }
                    )
                    HorizontalDivider()
                    ListItem(
                        modifier = Modifier.clickable {
                            showManageSelectedDialog = false
                            showConfirmDeleteSelected = true
                        },
                        headlineContent = {
                            Text("Delete",
                                color = MaterialTheme.colorScheme.error)
                        },
                        supportingContent = {
                            Text("Permanently remove the songs and their files from your library",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        leadingContent = {
                            Icon(Icons.Default.Delete, null,
                                tint = MaterialTheme.colorScheme.error)
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showManageSelectedDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showConfirmRemoveSelected) {
        AlertDialog(
            onDismissRequest = { showConfirmRemoveSelected = false },
            containerColor = MaterialTheme.colorScheme.background,
            title   = { Text("Remove from playlist?") },
            text    = {
                Text(
                    "${selectedIds.size} song${if (selectedIds.size == 1) "" else "s"} will be " +
                    "removed from this playlist. They stay in your library."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeSelectedFromPlaylist(playlistId)
                        showConfirmRemoveSelected = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor   = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) { Text("Remove") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirmRemoveSelected = false }) { Text("Cancel") }
            }
        )
    }

    if (showConfirmDeleteSelected) {
        AlertDialog(
            onDismissRequest = { showConfirmDeleteSelected = false },
            containerColor = MaterialTheme.colorScheme.background,
            title   = { Text("Delete selected songs?") },
            text    = {
                Text(
                    "${selectedIds.size} song${if (selectedIds.size == 1) "" else "s"} will be " +
                    "permanently removed from your library and their files deleted."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deletePlaylistSelected()
                        showConfirmDeleteSelected = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor   = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirmDeleteSelected = false }) { Text("Cancel") }
            }
        )
    }

    if (showConfirmRemoveSingle && pendingRemoveSong != null) {
        val song = pendingRemoveSong!!
        AlertDialog(
            onDismissRequest = {
                showConfirmRemoveSingle = false
                pendingRemoveSong = null
            },
            containerColor = MaterialTheme.colorScheme.background,
            title   = { Text("Remove from playlist?") },
            text    = {
                Text("\"${song.title}\" will be removed from this playlist. It stays in your library.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeSongFromPlaylist(playlistId, song.id)
                        showConfirmRemoveSingle = false
                        pendingRemoveSong = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor   = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) { Text("Remove") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showConfirmRemoveSingle = false
                    pendingRemoveSong = null
                }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistSongItem(
    song: SongEntity,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isInQueue: Boolean,
    isSelected: Boolean,
    inSelection: Boolean,
    isDragging: Boolean,
    dragHandleModifier: Modifier = Modifier,
    elevation: androidx.compose.ui.unit.Dp = 0.dp,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onRemoveFromQueue: () -> Unit,
    onToggleSelect: () -> Unit,
    onShareAsLink: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val haptic  = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }
    var showConfirmDelete by remember { mutableStateOf(false) }

    // In selection mode the blue highlight follows the selection (not the
    // currently-playing song); outside selection it marks the current song.
    val bgAlpha by animateFloatAsState(
        if (inSelection) { if (isSelected) 0.18f else 0f } else { if (isCurrent) 0.12f else 0f },
        label = "plSongBg"
    )

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
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (!inSelection && isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (!inSelection && isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false))
                if (isInQueue && !isCurrent && isDragging) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.Queue, null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                }
            }
        },
        supportingContent = { Text(song.artist, maxLines = 1) },
        leadingContent = {
            if (inSelection) {
                Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
            } else {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center) {
                    if (song.thumbnailUrl != null) {
                        AsyncImage(model = song.thumbnailUrl, contentDescription = null,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Icon(Icons.Default.MusicNote, null)
                    }
                    if (isCurrent) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                null, tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        },
        trailingContent = {
            if (!inSelection) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, "Options")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Select Multiple") },
                                leadingIcon = { Icon(Icons.Default.Checklist, null) },
                                onClick = {
                                    showMenu = false
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onToggleSelect()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Play Next") },
                                leadingIcon = { Icon(Icons.Default.SkipNext, null) },
                                onClick = { onPlayNext(); showMenu = false }
                            )
                            if (isInQueue) {
                                DropdownMenuItem(
                                    text = { Text("Remove from Queue") },
                                    leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, null) },
                                    onClick = { onRemoveFromQueue(); showMenu = false }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Add to Queue") },
                                    leadingIcon = { Icon(Icons.Default.Queue, null) },
                                    onClick = { onAddToQueue(); showMenu = false }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Share file") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = { shareSong(context, song); showMenu = false }
                            )
                            if (song.sourceUrl.startsWith("http")) {
                                DropdownMenuItem(
                                    text = { Text("Share link") },
                                    leadingIcon = { Icon(Icons.Default.Link, null) },
                                    onClick = { onShareAsLink(); showMenu = false }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Remove from Playlist",
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.75f)) },
                                leadingIcon = {
                                    Icon(Icons.Default.RemoveCircleOutline, null,
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.75f))
                                },
                                onClick = { onRemove(); showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, null,
                                        tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = { showMenu = false; showConfirmDelete = true }
                            )
                        }
                    }
                    Icon(
                        Icons.Default.DragHandle,
                        null,
                        modifier = dragHandleModifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            containerColor = MaterialTheme.colorScheme.background,
            title   = { Text("Delete song?") },
            text    = {
                Text("\"${song.title}\" will be permanently removed from your library and its file deleted.")
            },
            confirmButton = {
                Button(
                    onClick = { onDelete(); showConfirmDelete = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor   = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}
