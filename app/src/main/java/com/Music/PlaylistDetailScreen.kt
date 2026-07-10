package com.Music

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.Music.data.local.SongEntity
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onNavigateToPlayer: () -> Unit,
    // Called when a song is tapped from the playlist. Opens the big player
    // directly, skipping the mini player so the image only loads once.
    onPlayFromList: () -> Unit = { onNavigateToPlayer() }
) {
    val songs           by viewModel.playlistSongs.collectAsState()
    val playlists       by viewModel.playlists.collectAsState()
    val playlist        = playlists.find { it.playlist.id == playlistId }?.playlist
    val currentSong     by viewModel.currentSong.collectAsState()
    val isPlaying       by viewModel.isPlaying.collectAsState()
    val queue           by viewModel.queue.collectAsState()
    val selectedIds     by viewModel.playlistSelectedIds.collectAsState()
    val inSelection     = selectedIds.isNotEmpty()
    var showAddSelectedToPlaylist by remember { mutableStateOf(false) }

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
    BackHandler(enabled = inSelection) { viewModel.clearPlaylistSelection() }

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
                        IconButton(onClick = { viewModel.selectAllPlaylist() }) {
                            Icon(Icons.Default.SelectAll, "Select all")
                        }
                        IconButton(onClick = { viewModel.removeSelectedFromPlaylist(playlistId) }) {
                            Icon(
                                Icons.Default.DeleteSweep, "Remove selected from playlist",
                                tint = MaterialTheme.colorScheme.error
                            )
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
        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.MusicNote, null, Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                    Spacer(Modifier.height(8.dp))
                    Text("No songs in this playlist",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Add songs via the ⋮ menu in the library",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
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
            val currentSongs     by rememberUpdatedState(songs)
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
                            onClick  = { viewModel.playSongList(songs, 0); onPlayFromList() },
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
                                viewModel.playSongList(songs, 0)
                                onPlayFromList()
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

                itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
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
                                    enabled       = inSelection,
                                    onDragStarted = { viewModel.startDrag() },
                                    onDragStopped = { viewModel.endPlaylistDrag(playlistId) }
                                ),
                                elevation = elevation,
                                onPlay    = {
                                    if (song.id != currentSong?.id) {
                                        viewModel.playSongList(songs, index)
                                    }
                                    // Open the big player directly (skip mini
                                    // player) so the album art only loads in
                                    // the big player.
                                    onPlayFromList()
                                },
                                onPlayNext = { viewModel.playNext(song) },
                                onAddToQueue = { viewModel.addToQueue(song) },
                                onRemoveFromQueue = { viewModel.removeFromQueue(song.id) },
                                onToggleSelect = { viewModel.togglePlaylistSelect(song.id) },
                                onRemove  = { viewModel.removeSongFromPlaylist(playlistId, song.id) }
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
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

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
            if (inSelection) {
                // Reordering is only available in selection mode: show the
                // drag handle here so the user can move songs around while
                // the rest of the row toggles selection on tap.
                Icon(
                    Icons.Default.DragHandle,
                    null,
                    modifier = dragHandleModifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, "Options")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
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
                                text = { Text("Share") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = { shareSong(context, song); showMenu = false }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Remove from Playlist", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { onRemove(); showMenu = false }
                            )
                        }
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
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