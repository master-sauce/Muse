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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    onNavigateToLyrics: () -> Unit
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxHeightPx = with(density) { maxHeight.toPx() }

        // Sensitivity: a drag of `dragRangePx` covers the full 0→1 morph, so a
        // short flick is enough to open/close the player (like YT Music).
        val dragRangePx = maxHeightPx * 0.18f

        // Animate toward the target state whenever [expanded] flips. We start
        // from the *current* visual progress (including any live drag) so there
        // is no jump when a drag triggers onExpand/onCollapse or when back is
        // pressed mid-drag. Snappy and non-bouncy for a responsive feel.
        LaunchedEffect(expanded) {
            val currentProgress = (expansion.value - dragOffsetPx.value / dragRangePx)
                .coerceIn(0f, 1f)
            dragOffsetPx.snapTo(0f)
            expansion.snapTo(currentProgress)
            expansion.animateTo(
                targetValue   = if (expanded) 1f else 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness    = Spring.StiffnessMedium
                )
            )
        }

        // Combined progress 0..1 used to drive the morph.
        // Dragging up (negative offset) increases progress (expands); dragging
        // down (positive offset) decreases progress (collapses).
        val progress = (expansion.value - dragOffsetPx.value / dragRangePx)
            .coerceIn(0f, 1f)

        // ── Full player ───────────────────────────────────────────────────────
        // Slides up from below the viewport as progress → 1.
        if (progress > 0.001f) {
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
                        scope.launch { dragOffsetPx.snapTo(dragOffsetPx.value + dy) }
                    },
                    onDragEnd = {
                        val total = expansion.value - dragOffsetPx.value / dragRangePx
                        scope.launch {
                            if (total < 0.5f) onCollapse() else dragOffsetPx.animateTo(0f)
                        }
                    },
                    onDragCancel = {
                        scope.launch { dragOffsetPx.animateTo(0f) }
                    }
                )
            }
        }

        // ── Mini player ───────────────────────────────────────────────────────
        // Slides up following the finger and fades out as progress → 1.
        if (progress < 0.999f) {
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
                            scope.launch { dragOffsetPx.snapTo(dragOffsetPx.value + dy) }
                        },
                        onDragEnd = {
                            val total = expansion.value - dragOffsetPx.value / dragRangePx
                            scope.launch {
                                if (total > 0.35f) onExpand() else dragOffsetPx.animateTo(0f)
                            }
                        },
                        onDragCancel = {
                            scope.launch { dragOffsetPx.animateTo(0f) }
                        }
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
        var isDragging = false

        do {
            // Observe in the Initial pass so we see the event before children.
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.first()
            val dy = change.position.y - change.previousPosition.y

            if (isDragging) {
                change.consume()
                onDrag(dy)
            } else {
                totalDragY += dy
                if (abs(totalDragY) > touchSlop) {
                    isDragging = true
                    // Consume the historical movement too so children don't
                    // suddenly jump when we take over.
                    change.consume()
                }
            }
        } while (change.pressed)

        if (isDragging) onDragEnd() else onDragCancel()
    }
}
