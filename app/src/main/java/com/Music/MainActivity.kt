package com.Music

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.Music.data.local.SongEntity
import com.Music.ui.theme.MuseTheme
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MuseTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MusicPlayerScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerScreen(viewModel: MainViewModel = viewModel()) {
    val songs by viewModel.songs.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playbackProgress by viewModel.playbackProgress.collectAsState()
    val context = LocalContext.current

    val sheetState = rememberModalBottomSheetState()
    var showDownloader by remember { mutableStateOf(false) }

    // File picker for local audio import
    val pickAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importLocalSong(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.errorEvents.collectLatest { error ->
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Muse", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showDownloader = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Music")
                    }
                }
            )
        },
        bottomBar = {
            currentSong?.let { song ->
                BottomAppBar(
                    modifier = Modifier.height(110.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Column {
                        Slider(
                            value = playbackProgress,
                            onValueChange = { viewModel.seekTo(it) },
                            modifier = Modifier.padding(horizontal = 16.dp).height(24.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = song.thumbnailUrl,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                            IconButton(onClick = { viewModel.togglePlayback() }, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!showDownloader) {
                FloatingActionButton(onClick = { showDownloader = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Music")
                }
            }
        }
    ) { innerPadding ->
        if (songs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Your library is empty", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { showDownloader = true }) { Text("Add your first song") }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                items(songs, key = { it.id }) { song ->
                    SongItem(
                        song = song,
                        onPlay = { viewModel.playSong(song) },
                        onDelete = { viewModel.deleteSong(song) },
                        isCurrent = song.id == currentSong?.id
                    )
                }
            }
        }

        if (showDownloader) {
            ModalBottomSheet(onDismissRequest = { showDownloader = false }, sheetState = sheetState) {
                DownloaderSheet(
                    isDownloading = isDownloading,
                    isImporting = isImporting,
                    progress = downloadProgress,
                    onDownload = { url ->
                        viewModel.downloadSong(url)
                        showDownloader = false
                    },
                    onPickLocalFile = {
                        // audio/* covers mp3, flac, ogg, m4a etc.
                        pickAudioLauncher.launch(arrayOf("audio/*"))
                        showDownloader = false
                    }
                )
            }
        }
    }
}

@Composable
fun DownloaderSheet(
    isDownloading: Boolean,
    isImporting: Boolean,
    progress: Float,
    onDownload: (String) -> Unit,
    onPickLocalFile: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var urlText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Add Music", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))

        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("From Link") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("From Device") })
        }

        Spacer(modifier = Modifier.height(20.dp))

        when (selectedTab) {
            0 -> {
                // URL download tab
                TextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Paste YouTube, Spotify, or Apple Music link") },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { onDownload(urlText); urlText = "" },
                    enabled = !isDownloading && urlText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Downloading… ${progress.toInt()}%")
                    } else {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start Download")
                    }
                }
                if (isDownloading) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                    )
                }
            }

            1 -> {
                // Local file import tab
                Text(
                    "Pick an audio file from your device.\nMetadata (title, artist) is read automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onPickLocalFile,
                    enabled = !isImporting,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Importing…")
                    } else {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Browse Files")
                    }
                }
            }
        }
    }
}

@Composable
fun SongItem(song: SongEntity, onPlay: () -> Unit, onDelete: () -> Unit, isCurrent: Boolean) {
    ListItem(
        modifier = Modifier.clickable { onPlay() },
        headlineContent = {
            Text(
                song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
            )
        },
        supportingContent = { Text(song.artist, maxLines = 1) },
        leadingContent = {
            AsyncImage(
                model = song.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface
        )
    )
}