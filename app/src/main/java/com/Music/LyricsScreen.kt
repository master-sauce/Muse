package com.Music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Music.data.remote.LyricLine
import com.Music.data.remote.LyricsState

@Composable
fun LyricsScreen(viewModel: MainViewModel, onNavigateBack: () -> Unit) {
    val lyricsState by viewModel.lyrics.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val position    by viewModel.currentPosition.collectAsState()

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                MaterialTheme.colorScheme.background
            ))
        )
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.KeyboardArrowDown, "Back", Modifier.size(32.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Lyrics", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    currentSong?.let {
                        Text(it.title, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
                Spacer(Modifier.size(48.dp))
            }

            HorizontalDivider(Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))

            // The morphing PlayerOverlay pins a MiniPlayer bar on top of this
            // screen when a song is loaded. Reserve matching bottom space so the
            // lyrics aren't hidden behind it (same 84.dp inset LibraryScreen uses).
            val miniPlayerInset = if (currentSong != null) 84.dp else 0.dp
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = miniPlayerInset)
            ) {
                when (val state = lyricsState) {
                    is LyricsState.Idle         -> LyricsPlaceholder("Play a song to see lyrics")
                    is LyricsState.Loading      -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    is LyricsState.NotFound     -> LyricsPlaceholder("No lyrics found for this song")
                    is LyricsState.Instrumental -> LyricsPlaceholder("🎵  This track is instrumental")
                    is LyricsState.Plain        -> PlainLyrics(state.text)
                    is LyricsState.Synced       -> SyncedLyrics(state.lines, position)
                }
            }
        }
    }
}

@Composable
private fun LyricsPlaceholder(message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PlainLyrics(text: String) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp)
    ) {
        item {
            Text(
                text       = text,
                style      = MaterialTheme.typography.bodyLarge,
                color      = MaterialTheme.colorScheme.onBackground,
                fontSize   = 18.sp,
                lineHeight = 30.sp
            )
        }
    }
}

@Composable
private fun SyncedLyrics(lines: List<LyricLine>, positionMs: Long) {
    val listState    = rememberLazyListState()
    val currentIndex by remember(positionMs, lines) {
        derivedStateOf { lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0) }
    }

    LaunchedEffect(currentIndex) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(maxOf(0, currentIndex - 2))
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 80.dp)
    ) {
        itemsIndexed(lines) { index, line ->
            if (line.text.isBlank()) { Spacer(Modifier.height(12.dp)); return@itemsIndexed }
            val isCurrent = index == currentIndex
            val isPast    = index < currentIndex
            Text(
                text       = line.text,
                fontSize   = if (isCurrent) 24.sp else 19.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color      = when {
                    isCurrent -> MaterialTheme.colorScheme.primary
                    isPast    -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    else      -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.95f)
                },
                lineHeight = 34.sp,
                modifier   = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .then(
                        if (isCurrent) Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                        else Modifier
                    )
            )
        }
    }
}