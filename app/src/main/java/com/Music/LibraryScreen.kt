package com.Music

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.Music.data.local.SongEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: MainViewModel, onNavigateToPlayer: () -> Unit) {
    val songs         by viewModel.songs.collectAsState()
    val currentSong   by viewModel.currentSong.collectAsState()
    val isPlaying     by viewModel.isPlaying.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val dlProgress    by viewModel.downloadProgress.collectAsState()
    val isImporting   by viewModel.isImporting.collectAsState()

    val sheetState  = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showAdd     by remember { mutableStateOf(false) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.importLocalSong(it) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { viewModel.importFromFolder(it) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("Muse", fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.headlineMedium)
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Music")
                    }
                }
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = currentSong != null,
                enter = slideInVertically { it } + fadeIn(),
                exit  = slideOutVertically { it } + fadeOut()
            ) {
                currentSong?.let { song ->
                    MiniPlayer(
                        song       = song,
                        isPlaying  = isPlaying,
                        onToggle   = { viewModel.togglePlayback() },
                        onTap      = onNavigateToPlayer
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (songs.isEmpty()) {
                EmptyLibrary { showAdd = true }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(songs, key = { it.id }) { song ->
                        SongListItem(
                            song      = song,
                            isCurrent = song.id == currentSong?.id,
                            isPlaying = isPlaying && song.id == currentSong?.id,
                            onPlay    = { viewModel.playSong(song); onNavigateToPlayer() },
                            onDelete  = { viewModel.deleteSong(song) }
                        )
                    }
                }
            }
        }

        if (showAdd) {
            ModalBottomSheet(
                onDismissRequest = { showAdd = false },
                sheetState = sheetState
            ) {
                AddMusicSheet(
                    isDownloading    = isDownloading,
                    downloadProgress = dlProgress,
                    isImporting      = isImporting,
                    onDownload       = { url -> viewModel.downloadSong(url); showAdd = false },
                    onPickFile       = { pickFile.launch(arrayOf("audio/*")); showAdd = false },
                    onPickFolder     = { pickFolder.launch(null); showAdd = false }
                )
            }
        }
    }
}

// ─── Empty state ────────────────────────────────────────────────────────────

@Composable
private fun EmptyLibrary(onAdd: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.LibraryMusic, null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            )
            Spacer(Modifier.height(4.dp))
            Text("Library is empty", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Text("Add songs to get started", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onAdd) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add Music")
            }
        }
    }
}

// ─── Song row ────────────────────────────────────────────────────────────────

@Composable
fun SongListItem(
    song: SongEntity,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val bgAlpha by animateFloatAsState(
        targetValue = if (isCurrent) 0.22f else 0f,
        label = "songBg"
    )

    ListItem(
        modifier = Modifier
            .clickable { onPlay() }
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = bgAlpha)),
        headlineContent = {
            Text(
                song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingContent = {
            Box(
                modifier = Modifier.size(54.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (song.thumbnailUrl != null) {
                    AsyncImage(
                        model = song.thumbnailUrl, contentDescription = null,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.MusicNote, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Playing indicator overlay
                if (isCurrent) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            null, tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Delete",
                    tint = MaterialTheme.colorScheme.error)
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent
        )
    )
    HorizontalDivider(
        Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    )
}

// ─── Mini player ─────────────────────────────────────────────────────────────

@Composable
fun MiniPlayer(
    song: SongEntity,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onTap: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable { onTap() },
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (song.thumbnailUrl != null) {
                    AsyncImage(
                        model = song.thumbnailUrl, contentDescription = null,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.MusicNote, null)
                }
            }

            Column(
                Modifier.weight(1f).padding(horizontal = 12.dp)
            ) {
                Text(song.title, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            IconButton(onClick = onToggle) {
                AnimatedContent(
                    targetState = isPlaying,
                    transitionSpec = {
                        (scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn())
                            .togetherWith(scaleOut() + fadeOut())
                    },
                    label = "miniPlayPause"
                ) { playing ->
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (playing) "Pause" else "Play",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

// ─── Add Music sheet ─────────────────────────────────────────────────────────

@Composable
fun AddMusicSheet(
    isDownloading: Boolean,
    downloadProgress: Float,
    isImporting: Boolean,
    onDownload: (String) -> Unit,
    onPickFile: () -> Unit,
    onPickFolder: () -> Unit
) {
    var tab     by remember { mutableIntStateOf(0) }
    var urlText by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Add Music", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        TabRow(selectedTabIndex = tab) {
            listOf("Download", "File", "Folder").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }

        Spacer(Modifier.height(24.dp))

        // Fixed height prevents sheet jumping between tabs
        Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 200.dp)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (fadeIn(tween(200)) + slideInHorizontally(tween(280)) { dir * it / 3 })
                        .togetherWith(fadeOut(tween(150)) + slideOutHorizontally(tween(230)) { -dir * it / 3 })
                },
                label = "addTab"
            ) { t ->
                when (t) {
                    0 -> DownloadTabContent(isDownloading, downloadProgress, urlText,
                        onUrlChange = { urlText = it },
                        onDownload  = { onDownload(urlText); urlText = "" })
                    1 -> FileTabContent(isImporting, onPickFile)
                    else -> FolderTabContent(isImporting, onPickFolder)
                }
            }
        }
    }
}

@Composable
private fun DownloadTabContent(
    isDownloading: Boolean, progress: Float,
    urlText: String, onUrlChange: (String) -> Unit, onDownload: () -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = urlText, onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("YouTube, Spotify, or Apple Music URL") },
            singleLine = true, shape = RoundedCornerShape(12.dp),
            leadingIcon = { Icon(Icons.Default.Link, null) }
        )
        Button(
            onClick = onDownload,
            enabled = !isDownloading && urlText.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isDownloading) {
                CircularProgressIndicator(Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Downloading ${progress.toInt()}%")
            } else {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Download")
            }
        }
        AnimatedVisibility(visible = isDownloading) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            )
        }
    }
}

@Composable
private fun FileTabContent(isImporting: Boolean, onPickFile: () -> Unit) {
    Column(Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.AudioFile, null, Modifier.size(52.dp),
            tint = MaterialTheme.colorScheme.primary)
        Text("Import audio files from your device",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Text("Supports MP3, FLAC, M4A, OGG, WAV and more",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            onClick = onPickFile, enabled = !isImporting,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isImporting) {
                CircularProgressIndicator(Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Importing...")
            } else {
                Icon(Icons.Default.FolderOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("Browse Files")
            }
        }
    }
}

@Composable
private fun FolderTabContent(isImporting: Boolean, onPickFolder: () -> Unit) {
    Column(Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.Folder, null, Modifier.size(52.dp),
            tint = MaterialTheme.colorScheme.primary)
        Text("Import all songs from a folder",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Text("Every audio file in the folder gets added to your library",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            onClick = onPickFolder, enabled = !isImporting,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isImporting) {
                CircularProgressIndicator(Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Scanning folder...")
            } else {
                Icon(Icons.Default.CreateNewFolder, null)
                Spacer(Modifier.width(8.dp))
                Text("Choose Folder")
            }
        }
    }
}