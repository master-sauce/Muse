package com.Music

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MusicPlayerScreen()
                }
            }
        }
    }
}

@Composable
fun MusicPlayerScreen(viewModel: MainViewModel = viewModel()) {
    var urlText by remember { mutableStateOf("") }
    val songs by viewModel.songs.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playbackProgress by viewModel.playbackProgress.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.errorEvents.collectLatest { error ->
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.padding(16.dp).statusBarsPadding()) {
                Text("Muse", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Paste YouTube/Spotify/etc link") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            if (urlText.isNotBlank()) {
                                viewModel.downloadSong(urlText)
                                urlText = ""
                            }
                        },
                        enabled = !isDownloading,
                        modifier = Modifier.size(56.dp)
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = "Download")
                        }
                    }
                }
                if (isDownloading) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                        )
                        Text(
                            text = if (downloadProgress > 0) "Downloading: ${downloadProgress.toInt()}%" else "Resolving link...",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
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
                            modifier = Modifier.padding(horizontal = 8.dp).height(24.dp)
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
                            IconButton(
                                onClick = { viewModel.togglePlayback() },
                                modifier = Modifier.size(48.dp)
                            ) {
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
        }
    ) { innerPadding ->
        if (songs.isEmpty() && !isDownloading) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("No music yet. Paste a link above!", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    }
}

@Composable
fun SongItem(song: SongEntity, onPlay: () -> Unit, onDelete: () -> Unit, isCurrent: Boolean) {
    ListItem(
        modifier = Modifier.clickable { onPlay() },
        headlineContent = { 
            Text(
                song.title, 
                maxLines = 1, 
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
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
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
        )
    )
}
