package com.Music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A YouTube-Music / Samsung-Music style morphing player.
 *
 * Two visual states share the screen:
 *  - Collapsed (progress = 0): a small [MiniPlayer] bar pinned to the bottom.
 *  - Expanded  (progress = 1): a full-screen [PlayerContent].
 *
 * A single [expansion] Animatable (0f → 1f) drives the morph, and a live
 * [dragOffsetPx] is added on top while the user is dragging so the visuals
 * track the finger exactly. Releasing past a threshold snaps to the nearest
 * state via [onExpand] / [onCollapse].
 *
 * Touch handling:
 *  - Collapsed: the mini bar uses [tapAndVerticalDrag] so a tap opens the
 *    player and an upward drag expands it gradually. The screen behind stays
 *    scrollable because only the mini bar consumes touches.
 *  - Expanded : the full player's top bar uses [verticalDrag] so a downward
 *    drag collapses it gradually. The rest of the player (slider, buttons)
 *    stays interactive.
 */
@Composable
fun PlayerOverlay(
    viewModel: MainViewModel,
    expanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onNavigateToLyrics: () -> Unit,
    // When true, the overlay skips the mini-player phase on the initial
    // expand: the big player slides up directly (its image loads only once,
    // in the big player) instead of first showing the mini player's
    // thumbnail and then morphing. Used when a song is tapped from the list.
    // Reset to false by the caller once the expand animation finishes.
    openExpanded: Boolean = false,
    onOpenExpandedDone: () -> Unit = {}
) {
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying   by viewModel.isPlaying.collectAsState()

    // Nothing to show until a song has been loaded.
    val song = currentSong ?: return

    val density = LocalDensity.current
    val scope   = rememberCoroutineScope()

    // 0f = collapsed (mini), 1f = expanded (full player).
    val expansion = remember { Animatable(if (expanded) 1f else 0f) }

    // Live drag offset, reset whenever the target state changes so a back
    // press or programmatic collapse always starts from a clean slate (this
    // fixes the "half-open" bug when back is pressed mid-drag).
    val dragOffsetPx = remember { Animatable(0f) }

    // True while the user's finger is actively dragging. While this is set we
    // keep BOTH the mini bar and the full player composed so the gesture
    // target is never disposed mid-drag (which would cancel the drag and snap
    // the player back even though the finger is still down). The user can
    // therefore slide all the way from collapsed → expanded (or back) in a
    // single continuous motion and the UI keeps tracking the finger until
    // release.
    var isDragging by remember { mutableStateOf(false) }

    // ── Hero artwork morph ─────────────────────────────────────────────────
    // We measure the on-screen bounds of the mini player's thumbnail and the
    // full player's album art, then render a single "hero" AsyncImage on top
    // that interpolates its position/size/corner-radius between the two. The
    // inner images inside MiniPlayer/PlayerContent are hidden while morphing
    // so the hero is the only artwork visible — exactly like YouTube Music's
    // continuous thumbnail→album-art grow animation.
    var miniThumbRect by remember { mutableStateOf<IntRect?>(null) }
    var bigArtRect    by remember { mutableStateOf<IntRect?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxHeightPx = with(density) { maxHeight.toPx() }

        // Sensitivity: a drag of `dragRangePx` covers the full 0→1 morph.
        // 0.35 = a natural, full-length swipe; not too twitchy, not too far.
        val dragRangePx = maxHeightPx * 0.35f

        // Animate toward the target state whenever [expanded] flips. We start
        // from the *current* visual progress (including any live drag) so there
        // is no jump when a drag triggers onExpand/onCollapse or when back is
        // pressed mid-drag. Snappy and non-bouncy for a responsive feel.
        LaunchedEffect(expanded) {
            // Don't fight the user: if a drag is in progress, the drag end
            // callback is what flips `expanded` and drives the snap animation.
            if (isDragging) return@LaunchedEffect
            val currentProgress = (expansion.value - dragOffsetPx.value / dragRangePx)
                .coerceIn(0f, 1f)
            dragOffsetPx.snapTo(0f)
            expansion.snapTo(currentProgress)
            expansion.animateTo(
                targetValue   = if (expanded) 1f else 0f,
                // Snappier than StiffnessMedium (400f) but smoother than
                // StiffnessHigh (10000f) — a quick, responsive morph.
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness    = 1500f
                )
            )
            // Once the expand animation finishes, clear the openExpanded flag
            // so the mini player is available again for subsequent collapses.
            if (expanded && openExpanded) onOpenExpandedDone()
        }

        // Progress is hard-clamped to 0..1 so the player cannot be dragged
        // past its endpoints — it stops firmly at fully collapsed (0) or
        // fully expanded (1). Dragging up (negative offset) increases
        // progress (expands); dragging down (positive offset) decreases
        // progress (collapses).
        val progress = (expansion.value - dragOffsetPx.value / dragRangePx)
            .coerceIn(0f, 1f)

        // Hide the inner images only while actively morphing (0 < progress < 1).
        // At the endpoints the real image is shown so taps/gestures hit it.
        val morphing = progress > 0.001f && progress < 0.999f

        // ── Full player ───────────────────────────────────────────────────────
        // Slides up from below the viewport as progress → 1. Kept composed
        // while a drag is in progress so the gesture target is never disposed
        // mid-drag (which would cancel the drag and snap the player back).
        // Also render the full player from progress 0 when opening expanded
        // directly (the mini player is skipped in that mode, so the big player
        // must be visible immediately and slide up on its own).
        if (progress > 0.001f || isDragging || openExpanded) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = (progress * 2f).coerceIn(0f, 1f)
                        // At progress = 0 the full player sits one screen below;
                        // at progress = 1 it fills the screen at 0.
                        translationY = (1f - progress) * maxHeightPx
                    }
            ) {
                PlayerContent(
                    viewModel          = viewModel,
                    onNavigateBack     = onCollapse,
                    onNavigateToLyrics = onNavigateToLyrics,
                    showBackChevron    = progress > 0.6f,
                    onDragDown         = { dy ->
                        isDragging = true
                        scope.launch { dragOffsetPx.snapTo(dragOffsetPx.value + dy) }
                    },
                    onDragEnd = {
                        val total = expansion.value - dragOffsetPx.value / dragRangePx
                        isDragging = false
                        scope.launch {
                            // Collapse once the player has been dragged down past
                            // the 70% mark (i.e. only 30% of the morph remains).
                            if (total < 0.7f) onCollapse() else dragOffsetPx.animateTo(0f)
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        scope.launch { dragOffsetPx.animateTo(0f) }
                    },
                    // Let the user drag down from the album art too (YT Music).
                    onArtworkDragDown = { dy ->
                        isDragging = true
                        scope.launch { dragOffsetPx.snapTo(dragOffsetPx.value + dy) }
                    },
                    onArtworkDragEnd = {
                        val total = expansion.value - dragOffsetPx.value / dragRangePx
                        isDragging = false
                        scope.launch {
                            // Collapse once the player has been dragged down past
                            // the 70% mark (i.e. only 30% of the morph remains).
                            if (total < 0.7f) onCollapse() else dragOffsetPx.animateTo(0f)
                        }
                    },
                    onArtworkDragCancel = {
                        isDragging = false
                        scope.launch { dragOffsetPx.animateTo(0f) }
                    },
                    // Hero morph: report the album art's on-screen bounds and
                    // hide the inner image while morphing so the hero draws on
                    // top.
                    onArtworkPositioned = { rect -> bigArtRect = rect },
                    hideArtwork = morphing
                )
            }
        }

        // ── Mini player ───────────────────────────────────────────────────────
        // Slides up following the finger and fades out as progress → 1. Kept
        // composed while a drag is in progress for the same reason as above.
        // Skipped entirely while [openExpanded] is active so the mini player's
        // thumbnail never loads first — the big player opens directly.
        if ((progress < 0.999f || isDragging) && !openExpanded) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = (1f - progress * 2f).coerceIn(0f, 1f)
                        // Slide up off the top as the full player replaces it.
                        translationY = -progress * maxHeightPx
                    }
            ) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    MiniPlayer(
                        song       = song,
                        isPlaying  = isPlaying,
                        onToggle   = { viewModel.togglePlayback() },
                        onPrevious = { viewModel.playPrevious() },
                        onNext     = { viewModel.playNext() },
                        onTap      = onExpand,
                        onDragUp   = { dy ->
                            isDragging = true
                            scope.launch { dragOffsetPx.snapTo(dragOffsetPx.value + dy) }
                        },
                        onDragEnd = {
                            val total = expansion.value - dragOffsetPx.value / dragRangePx
                            isDragging = false
                            scope.launch {
                                // Past the 30% mark → expand; otherwise snap back.
                                if (total > 0.3f) onExpand() else dragOffsetPx.animateTo(0f)
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                            scope.launch { dragOffsetPx.animateTo(0f) }
                        },
                        // Hero morph: report the thumbnail's on-screen bounds
                        // and hide the inner image while morphing.
                        onThumbnailPositioned = { rect -> miniThumbRect = rect },
                        hideThumbnail = morphing
                    )
                }
            }
        }

        // ── Hero artwork ───────────────────────────────────────────────────────
        // A single AsyncImage drawn on top of both layers that continuously
        // grows from the mini thumbnail's bounds (progress = 0) to the big
        // album art's bounds (progress = 1). This is the YouTube-Music-style
        // "the picture expands" animation. Only drawn while we have both
        // measured bounds and are actively morphing.
        val mini = miniThumbRect
        val big  = bigArtRect
        if (morphing && mini != null && big != null && song.thumbnailUrl != null) {
            val p = progress
            // Interpolate left/top/width/height in pixels.
            val left   = (mini.left   + (big.left   - mini.left)   * p).roundToInt()
            val top    = (mini.top    + (big.top    - mini.top)    * p).roundToInt()
            val width  = (mini.width  + (big.width  - mini.width)  * p).roundToInt()
            val height = (mini.height + (big.height - mini.height) * p).roundToInt()
            // Corner radius: 12dp (mini) → 24dp (big), in px.
            val cornerPx = with(density) {
                (12.dp.toPx() + (24.dp.toPx() - 12.dp.toPx()) * p)
            }
            Box(
                Modifier
                    .fillMaxSize()
            ) {
                Box(
                    Modifier
                        .offset { androidx.compose.ui.unit.IntOffset(left, top) }
                        .size(
                            with(density) { width.toDp() },
                            with(density) { height.toDp() }
                        )
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(cornerPx))
                ) {
                    AsyncImage(
                        model              = song.thumbnailUrl,
                        contentDescription = "Album art",
                        modifier           = Modifier.fillMaxSize(),
                        contentScale       = ContentScale.Crop
                    )
                }
            }
        }
    }
}

// ─── Gesture helpers ──────────────────────────────────────────────────────────

/**
 * Detects a vertical drag using the [PointerEventPass.Initial] pass so it can
 * intercept a drag *before* child clickables (e.g. the play/pause buttons
 * inside the mini player) consume the pointer.
 *
 * Crucially, it does **not** consume the down event or call any callback for a
 * tap — so taps still flow through to children (and the card's own
 * `combinedClickable`). Only once the pointer moves past [touchSlop] do we
 * start consuming events and reporting drags. This is what lets the mini bar
 * be both draggable (to expand) and have working buttons.
 *
 * To avoid stealing gestures that are really horizontal (e.g. the seek
 * [Slider]), the drag only engages when the accumulated movement is
 * predominantly vertical (|dy| > |dx|). A horizontal swipe therefore stays
 * with the child, while a vertical swipe anywhere collapses/expands the
 * player — exactly like YouTube Music.
 */
fun Modifier.verticalDrag(
    touchSlop: Float,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
): Modifier = this.pointerInput(touchSlop, onDrag, onDragEnd, onDragCancel) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var totalDragY = 0f
        var totalDragX = 0f
        var isDragging = false
        // Once we decide this gesture is horizontal, stop competing for it so
        // the child (Slider) gets a clean stream of events.
        var yieldedToChild = false

        // A gesture is handed to the child (Slider) only when the horizontal
        // component is at least this many times the vertical one. 1.5f lets
        // comfortable diagonal drags (up to ~56° from vertical) still engage
        // the morph, while clearly horizontal swipes stay with the Slider.
        val horizontalRatio = 1.5f

        do {
            // Observe in the Initial pass so we see the event before children.
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.first()
            val dy = change.position.y - change.previousPosition.y
            val dx = change.position.x - change.previousPosition.x

            if (isDragging) {
                change.consume()
                onDrag(dy)
            } else if (yieldedToChild) {
                // Do nothing — let the child handle the rest of the gesture.
            } else {
                totalDragY += dy
                totalDragX += dx
                val absY = abs(totalDragY)
                val absX = abs(totalDragX)
                // Engage the vertical morph as soon as there is meaningful
                // vertical movement AND the gesture isn't strongly horizontal.
                // This relaxes the old strict |dy| > |dx| rule so diagonal
                // drags expand/collapse the player instead of being ignored,
                // while horizontal drift during an engaged drag never breaks it.
                if (absY > touchSlop && absX <= absY * horizontalRatio) {
                    isDragging = true
                    // Consume the historical movement too so children don't
                    // suddenly jump when we take over.
                    change.consume()
                } else if (absX > touchSlop && absX > absY * horizontalRatio) {
                    // Predominantly horizontal — hand the gesture to the child.
                    yieldedToChild = true
                }
            }
        } while (change.pressed)

        if (isDragging) onDragEnd() else onDragCancel()
    }
}
