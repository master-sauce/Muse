package com.Music

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.Music.data.local.SongEntity
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onNavigateToPlayer: () -> Unit
) {
    val songsFlow   = remember(playlistId) { viewModel.getPlaylistSongs(playlistId) }
    val songs       by songsFlow.collectAsState(emptyList())
    val playlists   by viewModel.playlists.collectAsState()
    val playlist     = playlists.find { it.playlist.id == playlistId }?.playlist
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying   by viewModel.isPlaying.collectAsState()
    val queue       by viewModel.queue.collectAsState()

    val haptic = LocalHapticFeedback.current

    Scaffold(
        topBar = {
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
    ) { padding ->
        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
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
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick  = { viewModel.playSongList(songs, 0); onNavigateToPlayer() },
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
                                onNavigateToPlayer()
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
                    val isInQueue = queue.any { it.mediaId == song.id }
                    
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = {
                            if (it == SwipeToDismissBoxValue.StartToEnd) {
                                viewModel.addToQueue(song)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                false // Don't actually dismiss the item from the list
                            } else false
                        }
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromEndToStart = false,
                        backgroundContent = {
                            val color = when (dismissState.dismissDirection) {
                                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
                                else -> Color.Transparent
                            }
                            Box(Modifier.fillMaxSize().background(color).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                                Icon(Icons.Default.Queue, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    ) {
                        PlaylistSongItem(
                            song      = song,
                            isCurrent = song.id == currentSong?.id,
                            isPlaying = isPlaying && song.id == currentSong?.id,
                            isInQueue = isInQueue,
                            onPlay    = {
                                viewModel.playSongList(songs, index)
                                onNavigateToPlayer()
                            },
                            onPlayNext = { viewModel.playNext(song) },
                            onAddToQueue = { viewModel.addToQueue(song) },
                            onRemoveFromQueue = { viewModel.removeFromQueue(song.id) },
                            onRemove  = { viewModel.removeSongFromPlaylist(playlistId, song.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistSongItem(
    song: SongEntity,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isInQueue: Boolean,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onRemoveFromQueue: () -> Unit,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.clickable { onPlay() },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false))
                if (isInQueue && !isCurrent) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.Queue, null, 
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                }
            }
        },
        supportingContent = { Text(song.artist, maxLines = 1) },
        leadingContent = {
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
        },
        trailingContent = {
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
