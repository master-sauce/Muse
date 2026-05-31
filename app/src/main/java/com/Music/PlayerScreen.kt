package com.Music

import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.Music.data.local.isVideo
import com.Music.data.remote.LyricsState

@OptIn(UnstableApi::class)
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
    val exoPlayer   by viewModel.exoPlayer.collectAsState()

    val isVideoFile = currentSong?.isVideo() == true
    var videoMode by remember(currentSong?.id) { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val activity = context as? androidx.activity.ComponentActivity

    // Lock orientation and hide system chrome in fullscreen
    LaunchedEffect(fullScreen) {
        if (fullScreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Back handler: exit fullscreen first, then navigate back
    BackHandler {
        if (fullScreen) {
            fullScreen = false
        } else {
            onNavigateBack()
        }
    }

    val albumScale by animateFloatAsState(
        targetValue   = if (isPlaying) 1f else 0.82f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow),
        label         = "albumScale"
    )
    val albumShadow by animateDpAsState(
        targetValue   = if (isPlaying) 32.dp else 6.dp,
        animationSpec = tween(500),
        label         = "albumShadow"
    )

    if (fullScreen && videoMode && isVideoFile) {
        // ── Fullscreen video mode ────────────────────────────────────────────
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            VideoPlayerView(
                player   = exoPlayer,
                modifier = Modifier.fillMaxSize(),
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            )

            // Tap to show/hide overlay
            var showOverlay by remember { mutableStateOf(true) }
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // Invisible tap target
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { showOverlay = !showOverlay }
                )
            }

            AnimatedVisibility(
                visible = showOverlay,
                enter   = fadeIn(tween(200)),
                exit    = fadeOut(tween(300))
            ) {
                // Top gradient + back button
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.scrim,
                                    MaterialTheme.colorScheme.scrim.copy(alpha = 0f)
                                )
                            )
                        )
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    IconButton(onClick = { fullScreen = false }) {
                        Icon(
                            Icons.Default.FullscreenExit,
                            "Exit fullscreen",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    } else {
        // ── Normal portrait mode ─────────────────────────────────────────────
        Surface(
            modifier = Modifier.fillMaxSize(),
            color    = MaterialTheme.colorScheme.background
        ) {
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
                    // ── Top bar ──────────────────────────────────────────────
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
                        IconButton(onClick = onNavigateToLyrics) {
                            Icon(Icons.Default.Lyrics, "Lyrics",
                                tint = when (lyricsState) {
                                    is LyricsState.Synced, is LyricsState.Plain ->
                                        MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                })
                        }
                    }

                    // ── Song / Video toggle ──────────────────────────────────
                    AnimatedVisibility(visible = isVideoFile) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            FilterChip(
                                selected    = !videoMode,
                                onClick     = { videoMode = false },
                                label       = { Text("Song") },
                                leadingIcon = { Icon(Icons.Default.MusicNote, null, Modifier.size(16.dp)) },
                                modifier    = Modifier.padding(end = 10.dp)
                            )
                            FilterChip(
                                selected    = videoMode,
                                onClick     = { videoMode = true },
                                label       = { Text("Video") },
                                leadingIcon = { Icon(Icons.Default.Videocam, null, Modifier.size(16.dp)) }
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // ── Album art or video view ──────────────────────────────
                    AnimatedContent(
                        targetState = videoMode && isVideoFile,
                        transitionSpec = {
                            (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.96f))
                                .togetherWith(fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f))
                        },
                        label = "artOrVideo"
                    ) { showVideo ->
                        if (showVideo) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .shadow(16.dp, RoundedCornerShape(20.dp))
                            ) {
                                VideoPlayerView(
                                    player     = exoPlayer,
                                    modifier   = Modifier.fillMaxSize(),
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                )

                                // Fullscreen button overlay
                                IconButton(
                                    onClick = { fullScreen = true },
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Fullscreen,
                                        "Fullscreen",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        } else {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .scale(albumScale)
                                    .shadow(
                                        albumShadow, RoundedCornerShape(24.dp),
                                        ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                        spotColor    = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                    )
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (currentSong?.thumbnailUrl != null) {
                                    AsyncImage(
                                        model              = currentSong!!.thumbnailUrl,
                                        contentDescription = "Album art",
                                        modifier           = Modifier.fillMaxSize(),
                                        contentScale       = ContentScale.Crop
                                    )
                                } else {
                                    Icon(Icons.Default.MusicNote, null, Modifier.size(96.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                }
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // ── Song info ────────────────────────────────────────────
                    Column(Modifier.fillMaxWidth()) {
                        AnimatedContent(
                            targetState    = currentSong?.title ?: "",
                            transitionSpec = {
                                (fadeIn(tween(220)) + slideInVertically(tween(220)) { -it / 3 })
                                    .togetherWith(fadeOut(tween(160)))
                            },
                            label = "title"
                        ) { title ->
                            Text(
                                title.ifEmpty { "Nothing playing" },
                                style      = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines   = 1,
                                overflow   = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        AnimatedContent(
                            targetState    = currentSong?.artist ?: "",
                            transitionSpec = {
                                (fadeIn(tween(220)) + slideInVertically(tween(220)) { -it / 3 })
                                    .togetherWith(fadeOut(tween(160)))
                            },
                            label = "artist"
                        ) { artist ->
                            Text(
                                artist,
                                style    = MaterialTheme.typography.bodyLarge,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // ── Seek bar ─────────────────────────────────────────────
                    Column(Modifier.fillMaxWidth()) {
                        Slider(
                            value = progress, onValueChange = { viewModel.seekTo(it) },
                            modifier = Modifier.fillMaxWidth(),
                            colors   = SliderDefaults.colors(
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

                    Spacer(Modifier.height(24.dp))

                    // ── Controls ─────────────────────────────────────────────
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
                            onClick  = { viewModel.togglePlayback() },
                            modifier = Modifier.size(72.dp),
                            shape    = CircleShape,
                            colors   = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            AnimatedContent(
                                targetState = isPlaying,
                                transitionSpec = {
                                    (scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn())
                                        .togetherWith(scaleOut() + fadeOut())
                                },
                                label = "playPause"
                            ) { playing ->
                                Icon(
                                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    if (playing) "Pause" else "Play",
                                    Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
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
    }
}

// ─── Native video surface ──────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerView(
    player: Player?,
    modifier: Modifier = Modifier,
    resizeMode: @AspectRatioFrameLayout.ResizeMode Int = AspectRatioFrameLayout.RESIZE_MODE_FIT
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController  = false
                this.resizeMode = resizeMode
                layoutParams   = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update    = { view ->
            view.player = player
            view.resizeMode = resizeMode
        },
        onRelease = { view -> view.player = null },
        modifier  = modifier
    )
}