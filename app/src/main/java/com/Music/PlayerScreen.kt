package com.Music

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun PlayerScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToLyrics: () -> Unit
) {
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying   by viewModel.isPlaying.collectAsState()
    val progress    by viewModel.playbackProgress.collectAsState()
    val position    by viewModel.currentPosition.collectAsState()
    val duration    by viewModel.duration.collectAsState()
    val isShuffled  by viewModel.isShuffled.collectAsState()
    val repeatMode  by viewModel.repeatMode.collectAsState()
    val lyricsState by viewModel.lyrics.collectAsState()

    val albumScale by animateFloatAsState(
        targetValue    = if (isPlaying) 1f else 0.82f,
        animationSpec  = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow),
        label          = "albumScale"
    )
    val albumShadow by animateDpAsState(
        targetValue   = if (isPlaying) 32.dp else 6.dp,
        animationSpec = tween(600),
        label         = "albumShadow"
    )

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                MaterialTheme.colorScheme.background,
                MaterialTheme.colorScheme.background
            ))
        )
    ) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Top bar ──────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.KeyboardArrowDown, "Back", Modifier.size(32.dp))
                }
                Text("Now Playing", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Lyrics button — tinted when lyrics are available
                IconButton(onClick = onNavigateToLyrics) {
                    Icon(Icons.Default.Lyrics, "Lyrics",
                        tint = when (lyricsState) {
                            is com.Music.data.remote.LyricsState.Synced,
                            is com.Music.data.remote.LyricsState.Plain ->
                                MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        })
                }
            }

            Spacer(Modifier.weight(1f))

            // ── Album art ────────────────────────────────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .scale(albumScale)
                    .shadow(albumShadow, RoundedCornerShape(24.dp),
                        ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        spotColor    = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (currentSong?.thumbnailUrl != null) {
                    AsyncImage(model = currentSong!!.thumbnailUrl, contentDescription = "Album art",
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Default.MusicNote, null, Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                }
            }

            Spacer(Modifier.weight(1f))

            // ── Song info ────────────────────────────────────────────────────
            Column(Modifier.fillMaxWidth()) {
                AnimatedContent(currentSong?.title ?: "",
                    transitionSpec = {
                        (fadeIn(tween(300)) + slideInVertically(tween(300)) { -it / 2 })
                            .togetherWith(fadeOut(tween(200)))
                    }, label = "title"
                ) { title ->
                    Text(title.ifEmpty { "Nothing playing" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                AnimatedContent(currentSong?.artist ?: "",
                    transitionSpec = {
                        (fadeIn(tween(300)) + slideInVertically(tween(300)) { -it / 2 })
                            .togetherWith(fadeOut(tween(200)))
                    }, label = "artist"
                ) { artist ->
                    Text(artist, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Seek bar ─────────────────────────────────────────────────────
            Column(Modifier.fillMaxWidth()) {
                Slider(
                    value = progress, onValueChange = { viewModel.seekTo(it) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor         = MaterialTheme.colorScheme.primary,
                        activeTrackColor   = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                    )
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(position.toTimeString(), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(duration.toTimeString(), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── Controls ─────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.toggleShuffle() }) {
                    Icon(Icons.Default.Shuffle, "Shuffle", Modifier.size(22.dp),
                        tint = if (isShuffled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { viewModel.playPrevious() }, Modifier.size(52.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(36.dp))
                }
                FilledIconButton(
                    onClick   = { viewModel.togglePlayback() },
                    modifier  = Modifier.size(72.dp),
                    shape     = CircleShape,
                    colors    = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    AnimatedContent(isPlaying,
                        transitionSpec = {
                            (scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn())
                                .togetherWith(scaleOut() + fadeOut())
                        }, label = "playPause"
                    ) { playing ->
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (playing) "Pause" else "Play",
                            Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                IconButton(onClick = { viewModel.playNext() }, Modifier.size(52.dp)) {
                    Icon(Icons.Default.SkipNext, "Next", Modifier.size(36.dp))
                }
                IconButton(onClick = { viewModel.toggleRepeat() }) {
                    when (repeatMode) {
                        RepeatMode.NONE -> Icon(Icons.Default.Repeat, "Repeat off",
                            Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        RepeatMode.ALL  -> Icon(Icons.Default.Repeat, "Repeat all",
                            Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        RepeatMode.ONE  -> Icon(Icons.Default.RepeatOne, "Repeat one",
                            Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(Modifier.weight(0.4f))
        }
    }
}