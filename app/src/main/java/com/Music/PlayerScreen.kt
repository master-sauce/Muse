package com.Music

import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.Music.data.local.isVideo
import com.Music.data.remote.LyricsState

@OptIn(UnstableApi::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerContent(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToLyrics: () -> Unit,
    showBackChevron: Boolean = true,
    onDragDown: ((Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onDragCancel: (() -> Unit)? = null,
    onArtworkDragDown: ((Float) -> Unit)? = null,
    onArtworkDragEnd: (() -> Unit)? = null,
    onArtworkDragCancel: (() -> Unit)? = null,
    // When non-null, the album art reports its on-screen bounds here and is
    // hidden (the hero image in PlayerOverlay draws on top during the morph).
    onArtworkPositioned: ((androidx.compose.ui.unit.IntRect) -> Unit)? = null,
    hideArtwork: Boolean = false
) {
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying   by viewModel.isPlaying.collectAsState()
    val progress    by viewModel.playbackProgress.collectAsState()
    val position    by viewModel.currentPosition.collectAsState()
    val duration    by viewModel.duration.collectAsState()
    // Use the user's shuffle *intent* (not the live player flag) so the button
    // stays highlighted even while a manual queue holds actual shuffle off —
    // the pending restore will re-apply shuffle once the queue drains.
    val isShuffled  by viewModel.shuffleIntent.collectAsState()
    val repeatMode  by viewModel.repeatMode.collectAsState()
    val lyricsState by viewModel.lyrics.collectAsState()
    val exoPlayer   by viewModel.exoPlayer.collectAsState()
    val playlists   by viewModel.playlists.collectAsState()
    val upNext      by viewModel.upNext.collectAsState()
    val timelineSize by viewModel.timelineSize.collectAsState()
    val reshuffleGeneration by viewModel.reshuffleGeneration.collectAsState()
    // The playlist the current playback list came from (null when playing from
    // the Library). Drives the "Remove from playlist" overflow-menu option.
    val playingPlaylistId by viewModel.playingPlaylistId.collectAsState()
    // Look up the playlist (with its songs) so the overflow menu can both name
    // the playlist and check whether the currently-playing song is still a
    // member of it. The playlists flow re-emits after a membership change (see
    // MusicRepository.playlistsWithSongs), so this drops the "Remove from
    // playlist" option as soon as the song is no longer in the playlist.
    val playingPlaylistWithSongs = playingPlaylistId?.let { id ->
        playlists.firstOrNull { it.playlist.id == id }
    }
    val playingPlaylist = playingPlaylistWithSongs?.playlist

    val isVideoFile = currentSong?.isVideo() == true
    var videoMode by remember(currentSong?.id) { mutableStateOf(false) }
    var fullScreen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val haptic  = LocalHapticFeedback.current
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

    // "Up Next" queue panel: revealed by long-pressing the album art. Overlays
    // the art in-place (shares its square footprint) so the song info below is
    // never pushed/squished. Tapping an item jumps the player to it; tapping
    // the art again, tapping the panel's close button, or pressing back hides it.
    var showQueuePanel by remember { mutableStateOf(false) }

    // Back handler: exit fullscreen → hide queue panel → navigate back.
    BackHandler {
        when {
            fullScreen     -> fullScreen = false
            showQueuePanel -> showQueuePanel = false
            else           -> onNavigateBack()
        }
    }
    // Separate handler so the panel closes even while fullscreen isn't active
    // but the user lands on this screen with it open.
    BackHandler(enabled = showQueuePanel && !fullScreen) { showQueuePanel = false }

    // The album art used to scale down to 0.82 when paused. That conflicted
    // with the hero morph (the hero is always at scale 1f, so handing off to
    // a 0.82-scaled image caused a visible "shrink" pop). Keep it at 1f.
    val albumScale = 1f
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
                            tint = Color.White
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
            // Overflow (three-dots) menu + "Add to Playlist" dialog state.
            // Declared here (Surface scope) so the dialog, rendered as a
            // sibling of the root Box below, can read/write them too.
            var showOverflowMenu by remember { mutableStateOf(false) }
            var showAddToPlaylist by remember { mutableStateOf(false) }
            // Confirm dialogs for the delete actions added to the overflow menu.
            var showConfirmDelete by remember { mutableStateOf(false) }
            var showConfirmRemoveFromPlaylist by remember { mutableStateOf(false) }

            // A single vertical-drag handle on the root Box lets the user
            // drag down from anywhere in the player to collapse it (like
            // YouTube Music). It uses the Initial pointer pass and only
            // consumes events once a *vertical* drag past touch slop is
            // detected, so:
            //  - Taps on buttons/lyrics still work (no movement → no consume).
            //  - The seek Slider still works: it consumes the pointer on its
            //    own Main pass for horizontal movement, so our Initial-pass
            //    vertical detector never reaches the slop threshold there.
            val touchSlop = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }
            // DOWN-only: the big player collapses on a downward swipe, but an
            // upward swipe is left free for the album art to use as a
            // swipe-up-to-reveal-queue gesture (see the artwork Box below).
            // Disabled while the "Up Next" queue panel is open so that dragging
            // down inside the panel scrolls the list (and reorders via the
            // drag handles) instead of collapsing the player. The panel is
            // dismissed via its close button, tapping the artwork, or back.
            val rootDragModifier =
                if (onDragDown != null && onDragEnd != null && onDragCancel != null && !showQueuePanel) {
                    Modifier.verticalDrag(
                        touchSlop, onDragDown, onDragEnd, onDragCancel,
                        dragDirection = VerticalDragDirection.DOWN
                    )
                } else Modifier
            Box(
                Modifier
                    .fillMaxSize()
                    .then(rootDragModifier)
                    .background(
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
                    // ── Top bar ────────────────────────────────────────────────
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        // Reserve the same 48dp width whether or not the chevron is
                        // visible so the "Now Playing" label stays centered during
                        // the drag-to-collapse gesture.
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.CenterStart) {
                            if (showBackChevron) {
                                IconButton(onClick = onNavigateBack) {
                                    Icon(Icons.Default.KeyboardArrowDown, "Back", Modifier.size(32.dp))
                                }
                            }
                        }
                        Text("Now Playing", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onNavigateToLyrics) {
                                Icon(Icons.Default.Lyrics, "Lyrics",
                                    tint = when (lyricsState) {
                                        is LyricsState.Synced, is LyricsState.Plain ->
                                            MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    })
                            }
                            // Three-dots overflow: Share file / Share link /
                            // Add to Playlist for the currently-playing song.
                            // Mirrors the per-song menu in the Library list.
                            Box {
                                IconButton(
                                    onClick = { showOverflowMenu = true },
                                    enabled = currentSong != null
                                ) {
                                    Icon(
                                        Icons.Default.MoreVert, "More options",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                DropdownMenu(
                                    expanded         = showOverflowMenu,
                                    onDismissRequest = { showOverflowMenu = false }
                                ) {
                                    val song = currentSong
                                    DropdownMenuItem(
                                        text        = { Text("Add to Playlist") },
                                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) },
                                        enabled     = playlists.isNotEmpty(),
                                        onClick     = {
                                            showOverflowMenu = false
                                            showAddToPlaylist = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text        = { Text("Share file") },
                                        leadingIcon = { Icon(Icons.Default.Share, null) },
                                        onClick     = {
                                            showOverflowMenu = false
                                            if (song != null) shareSong(context, song)
                                        }
                                    )
                                    if (song != null && song.sourceUrl.startsWith("http")) {
                                        DropdownMenuItem(
                                            text        = { Text("Share link") },
                                            leadingIcon = { Icon(Icons.Default.Link, null) },
                                            onClick     = {
                                                showOverflowMenu = false
                                                viewModel.shareSongAsLink(song)
                                            }
                                        )
                                    }
                                    // Delete actions for the currently-playing
                                    // song, mirroring the per-row menus in the
                                    // Library and Playlist Detail screens. Each
                                    // opens its own confirm dialog below.
                                    if (song != null) {
                                        HorizontalDivider()
                                        // Only offer "Remove from playlist" when the
                                        // currently-playing song is actually still a
                                        // member of the playlist playback came from.
                                        // The playlists flow re-emits after a removal
                                        // (see MusicRepository.playlistsWithSongs),
                                        // so this gate drops the option as soon as the
                                        // song is no longer in the playlist — keeping
                                        // the player's menu in sync with the DB.
                                        val songStillInPlaylist = playingPlaylistWithSongs?.songs
                                            ?.any { it.id == song.id } == true
                                        if (songStillInPlaylist) {
                                            DropdownMenuItem(
                                                text        = {
                                                    Text(
                                                        "Remove from \"${playingPlaylist?.name}\"",
                                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Default.RemoveCircleOutline, null,
                                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
                                                    )
                                                },
                                                onClick = {
                                                    showOverflowMenu = false
                                                    showConfirmRemoveFromPlaylist = true
                                                }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text        = {
                                                Text("Delete from library",
                                                    color = MaterialTheme.colorScheme.error)
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.Delete, null,
                                                    tint = MaterialTheme.colorScheme.error)
                                            },
                                            onClick = {
                                                showOverflowMenu = false
                                                showConfirmDelete = true
                                            }
                                        )
                                    }
                                }
                            }
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
                                        .background(Color.Black.copy(alpha = 0.3f), CircleShape)
                                ) {
                                    Icon(
                                        Icons.Default.Fullscreen,
                                        "Fullscreen",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        } else {
                            // The root Box already provides a DOWN-only
                            // drag-to-collapse handle for the whole player, so
                            // no separate gesture detector is needed here. The
                            // artwork itself is long-pressable (via
                            // combinedClickable below) to toggle the "Up Next"
                            // queue panel, which drops down below the art.
                            //
                            // To keep the song info / seek bar / controls from
                            // getting squished when the panel opens, the art
                            // shrinks (animated) while the panel is visible —
                            // the freed vertical space is taken by the panel.
                            val artFraction by animateFloatAsState(
                                targetValue = if (showQueuePanel) 0.62f else 1f,
                                animationSpec = tween(220),
                                label = "artFraction"
                            )
                            Box(
                                Modifier
                                    // Shrink the square art to `artFraction` of
                                    // the available width (and, because it stays
                                    // square via aspectRatio(1f), the same
                                    // fraction of its full height) while the
                                    // queue panel is open — freeing vertical
                                    // room below it so the song info isn't
                                    // squished. The parent Column centers
                                    // horizontally, so the narrower art stays
                                    // centered as it shrinks.
                                    .fillMaxWidth(artFraction)
                                    .aspectRatio(1f)
                                    .then(
                                        if (onArtworkPositioned != null) {
                                            Modifier.onGloballyPositioned { coords ->
                                                val pos = coords.positionInRoot()
                                                val size = coords.size
                                                onArtworkPositioned(
                                                    androidx.compose.ui.unit.IntRect(
                                                        left   = pos.x.toInt(),
                                                        top    = pos.y.toInt(),
                                                        right  = (pos.x + size.width).toInt(),
                                                        bottom = (pos.y + size.height).toInt()
                                                    )
                                                )
                                            }
                                        } else Modifier
                                    )
                                    .scale(albumScale)
                                    .shadow(
                                        albumShadow, RoundedCornerShape(24.dp),
                                        ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                        spotColor    = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                    )
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            showQueuePanel = !showQueuePanel
                                        },
                                        onDoubleClick = {
                                            // Double-tap the artwork toggles the
                                            // "Up Next" queue panel, same as
                                            // long-press (more discoverable).
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            showQueuePanel = !showQueuePanel
                                        },
                                        onClick = {
                                            // Tap the artwork to dismiss the
                                            // queue panel if it's open (a tap
                                            // otherwise does nothing).
                                            if (showQueuePanel) showQueuePanel = false
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!hideArtwork) {
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
                    }

                    // ── "Up Next" queue panel ───────────────────────────────
                    // Drops down just below the album art when the user
                    // long-presses it. Lists every upcoming media item in the
                    // player's timeline (after the current song); tapping one
                    // jumps the player to it. The art shrinks while this is
                    // open (see artFraction above) so the song info below keeps
                    // its room and isn't squished. Grows up to ~34% of the
                    // player height then scrolls internally for long queues.
                    AnimatedVisibility(
                        visible = showQueuePanel,
                        enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                        exit  = fadeOut(tween(140)) + shrinkVertically(tween(180))
                    ) {
                        UpNextPanel(
                            items = upNext,
                            totalCount = timelineSize,
                            reshuffleGeneration = reshuffleGeneration,
                            onPlay = { item ->
                                viewModel.playTimelineItem(item)
                                showQueuePanel = false
                            },
                            onQueueItem = { item -> viewModel.queueTimelineItem(item) },
                            onRemove = { item -> viewModel.removeUpNextItem(item) },
                            onReshuffle = { viewModel.reshuffle() },
                            onClose = { showQueuePanel = false },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .heightIn(max = 260.dp)
                        )
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
                        var sliderPosition by remember { mutableFloatStateOf(0f) }
                        var isDragging by remember { mutableStateOf(false) }

                        LaunchedEffect(progress) {
                            if (!isDragging) {
                                sliderPosition = progress
                            }
                        }

                        Slider(
                            value = sliderPosition,
                            onValueChange = {
                                isDragging = true
                                sliderPosition = it
                            },
                            onValueChangeFinished = {
                                viewModel.seekTo(sliderPosition)
                                isDragging = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors   = SliderDefaults.colors(
                                thumbColor         = MaterialTheme.colorScheme.primary,
                                activeTrackColor   = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                            )
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val displayPos = if (isDragging) (sliderPosition * duration).toLong() else position
                            Text(displayPos.toTimeString(), style = MaterialTheme.typography.labelMedium,
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

            // ── Add to Playlist dialog ─────────────────────────────────────
            // Spawned from the overflow menu above. Same dialog the Library
            // list rows use; lives in com.Music.LibraryScreen (same package).
            if (showAddToPlaylist) {
                AddToPlaylistDialog(
                    playlists = playlists.map { it.playlist },
                    onSelect  = { plId ->
                        currentSong?.let { viewModel.addSongToPlaylist(plId, it.id) }
                        showAddToPlaylist = false
                    },
                    onDismiss = { showAddToPlaylist = false }
                )
            }

            // ── Delete-from-library confirm ────────────────────────────────
            // Mirrors the per-row confirm in the Library list. Permanently
            // removes the currently-playing song (and its file) from the
            // library and drops it from the player's timeline.
            if (showConfirmDelete && currentSong != null) {
                val song = currentSong!!
                AlertDialog(
                    onDismissRequest = { showConfirmDelete = false },
                    containerColor = MaterialTheme.colorScheme.background,
                    title   = { Text("Delete song?") },
                    text    = {
                        Text("\"${song.title}\" will be permanently removed from your library and its file deleted.")
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.deleteSong(song)
                                showConfirmDelete = false
                            },
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

            // ── Remove-from-playlist confirm ───────────────────────────────
            // Only reachable when playback came from a playlist. Removes the
            // currently-playing song from that playlist; the song stays in the
            // library. Mirrors the per-row confirm in PlaylistDetailScreen.
            if (showConfirmRemoveFromPlaylist && currentSong != null && playingPlaylist != null) {
                val song = currentSong!!
                val plName = playingPlaylist!!.name
                AlertDialog(
                    onDismissRequest = { showConfirmRemoveFromPlaylist = false },
                    containerColor = MaterialTheme.colorScheme.background,
                    title   = { Text("Remove from playlist?") },
                    text    = {
                        Text("\"${song.title}\" will be removed from \"$plName\". It stays in your library.")
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.removeCurrentSongFromPlaylist()
                                showConfirmRemoveFromPlaylist = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor   = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) { Text("Remove") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showConfirmRemoveFromPlaylist = false }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}

// ─── "Up Next" panel ──────────────────────────────────────────────────────────

/**
 * A compact dropdown listing the songs coming up after the currently-playing
 * one in the player's timeline. Surfaced by swiping up on the album art in
 * [PlayerContent]. Tapping a row jumps the player to that item (via
 * [MainViewModel.playTimelineItem]); the panel itself is dismissed by the
 * caller. Capped at [maxHeight] and scrolls internally for long queues.
 *
 * Swipe a row end-to-start (right-to-left) to remove it from the timeline via
 * [onRemove] (mirrors the Queue tab), or start-to-end (left-to-right) to add it
 * to the manual queue (appended after any already-queued songs) via
 * [onQueueItem]. Reordering via drag is intentionally disabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpNextPanel(
    items: List<MediaItem>,
    onPlay: (MediaItem) -> Unit,
    onQueueItem: (MediaItem) -> Unit,
    onRemove: (MediaItem) -> Unit,
    totalCount: Int = items.size,
    reshuffleGeneration: Int = 0,
    modifier: Modifier = Modifier,
    onReshuffle: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        tonalElevation = 2.dp
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Upcoming, null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Up Next",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "$totalCount song${if (totalCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                if (onReshuffle != null) {
                    Icon(
                        Icons.Default.Shuffle, "Reshuffle",
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onReshuffle() },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(16.dp))
                }
                if (onClose != null) {
                    Icon(
                        Icons.Default.Close, "Close",
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onClose() },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            // Hoisted to panel scope so the reshuffle LaunchedEffect below can
            // drive it. scrollToItem(0) snaps the list to the top, showing the
            // new next song right after a reshuffle.
            val lazyListState = rememberLazyListState()
            LaunchedEffect(reshuffleGeneration) {
                if (reshuffleGeneration > 0 && items.isNotEmpty()) {
                    lazyListState.scrollToItem(0)
                }
            }
            if (items.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Nothing else queued",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Swipe gestures only (reordering via drag is disabled):
                //  • end-to-start (right-to-left) → remove the row from the
                //    timeline via onRemove (mirrors the Queue tab).
                //  • start-to-end (left-to-right) → add the row to the manual
                //    queue (appended after any already-queued songs) via
                //    onQueueItem.
                //
                // confirmValueChange returns false for both on purpose: we
                // trigger the action ourselves and never want the box to *park*
                // at the dismissed value. If it did, its colored background
                // would stay drawn (and could be inherited by the slot that
                // slides up into the dismissed row's place) — the "ghost that
                // won't go away" bug. By returning false the box always snaps
                // back toward rest; for removal the list then drops the item
                // (disposing the row's composition before any dismissed state
                // can linger), and for queue-add the row simply returns to its
                // place (now relocated to the queue zone by the viewmodel). Pass
                // `item` (identity), not `index`: the row's positional index can
                // be stale by the time the confirm callback fires.
                LazyColumn(state = lazyListState, modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(items, key = { _, item -> item.mediaId }) { index, item ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = {
                                when (it) {
                                    SwipeToDismissBoxValue.StartToEnd -> onQueueItem(item)
                                    SwipeToDismissBoxValue.EndToStart -> onRemove(item)
                                    else -> {}
                                }
                                false
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                val isQueueAdd =
                                    dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(
                                            if (isQueueAdd) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.errorContainer
                                        )
                                        .padding(horizontal = 20.dp),
                                    contentAlignment =
                                        if (isQueueAdd) Alignment.CenterStart else Alignment.CenterEnd
                                ) {
                                    Icon(
                                        if (isQueueAdd) Icons.Default.PlaylistAdd else Icons.Default.Delete,
                                        if (isQueueAdd) "Add to queue" else "Remove",
                                        tint = if (isQueueAdd) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        ) {
                            UpNextRow(
                                position = index + 1,
                                item = item,
                                onClick = { onPlay(item) }
                            )
                        }
                        if (index < items.lastIndex) {
                            HorizontalDivider(
                                Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpNextRow(
    position: Int,
    item: MediaItem,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            // Opaque surface at rest so the SwipeToDismissBox's colored dismiss
            // background (and the icon behind it) stay hidden until the user
            // actually swipes — the content layer slides away to reveal them.
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        headlineContent = {
            Text(
                item.mediaMetadata.title?.toString() ?: "Unknown",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                item.mediaMetadata.artist?.toString() ?: "Unknown",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (item.mediaMetadata.artworkUri != null) {
                    AsyncImage(
                        model = item.mediaMetadata.artworkUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        "$position",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
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
