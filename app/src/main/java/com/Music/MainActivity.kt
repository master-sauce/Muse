package com.Music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.Music.data.local.SongEntity
import com.Music.ui.theme.MuseTheme

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

    Scaffold(
        topBar = {
            Column(modifier = Modifier.padding(16.dp).statusBarsPadding()) {
                Text("Muse Downloader", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Paste YouTube/Spotify URL") },
                        singleLine = true
                    )
                    IconButton(
                        onClick = {
                            if (urlText.isNotBlank()) {
                                viewModel.downloadSong(urlText)
                                urlText = ""
                            }
                        },
                        enabled = !isDownloading
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Download")
                    }
                }
                if (isDownloading) {
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            items(songs) { song ->
                SongItem(
                    song = song,
                    onPlay = { /* TODO: Play song */ },
                    onDelete = { viewModel.deleteSong(song) }
                )
            }
        }
    }
}

@Composable
fun SongItem(song: SongEntity, onPlay: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        headlineContent = { Text(song.title) },
        supportingContent = { Text(song.artist) },
        leadingContent = {
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play")
            }
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    )
}
