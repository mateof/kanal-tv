package com.mateof.kanal.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mateof.kanal.ui.theme.KanalColors
import kotlin.math.roundToLong

/**
 * Progress bar that can be moved: dragged or tapped with a finger, and scrubbed
 * with the left and right keys of a remote once it has the focus.
 *
 * The position shown while scrubbing is local to the bar. Seeking on every key
 * press would make the player re-buffer a dozen times crossing a film, so the
 * target settles for a moment — or the user presses OK — before it is committed.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onScrub: (Long?) -> Unit = {}
) {
    if (durationMs <= 0) return

    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val focus = remember { FocusRequester() }

    // Non-null while the user is moving it; the player is still where it was.
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val held = remember { HeldSeek() }
    var commitAt by remember { mutableLongStateOf(0L) }

    val shownMs = scrubMs ?: positionMs
    val fraction = (shownMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    LaunchedEffect(scrubMs) { onScrub(scrubMs) }

    // Settle: a pause after the key is let go commits the seek without needing
    // OK. Counted from the key-up, never from a key-down: the first repeat of a
    // held key arrives half a second after the press, and a settle timed from
    // the press committed in that gap — the player started buffering, and the
    // next repeat moved it again.
    LaunchedEffect(commitAt) {
        if (commitAt == 0L) return@LaunchedEffect
        kotlinx.coroutines.delay(SEEK_SETTLE_MS)
        scrubMs?.let { target ->
            scrubMs = null
            onSeek(target)
        }
    }

    val height by animateDpAsState(
        targetValue = if (focused || scrubMs != null) 10.dp else 5.dp,
        animationSpec = tween(140),
        label = "seekBarHeight"
    )

    fun nudge(direction: Int, repeat: Int) {
        val from = scrubMs ?: positionMs
        scrubMs = (from + direction * held.step(repeat)).coerceIn(0L, durationMs)
        commitAt = 0L
    }

    fun commitNow() {
        val target = scrubMs ?: return
        scrubMs = null
        commitAt = 0L
        onSeek(target)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .focusRequester(focus)
            .focusable(interactionSource = interactions)
            .onKeyEvent { event ->
                val arrow = event.key == Key.DirectionLeft || event.key == Key.DirectionRight
                if (event.type == KeyEventType.KeyUp && arrow && scrubMs != null) {
                    commitAt = System.currentTimeMillis()
                    return@onKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val repeat = event.nativeKeyEvent.repeatCount
                when (event.key) {
                    Key.DirectionLeft -> { nudge(-1, repeat); true }
                    Key.DirectionRight -> { nudge(1, repeat); true }
                    Key.DirectionCenter, Key.Enter -> {
                        if (scrubMs != null) { commitNow(); true } else false
                    }

                    else -> false
                }
            }
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val barWidth = maxWidth

        fun seekToX(x: Float) {
            if (widthPx <= 0f) return
            val target = ((x / widthPx).coerceIn(0f, 1f) * durationMs).roundToLong()
            scrubMs = target
            commitAt = System.currentTimeMillis()
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(durationMs) {
                    detectTapGestures { offset -> seekToX(offset.x); commitNow() }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> seekToX(offset.x) },
                        onDragEnd = { commitNow() },
                        onDragCancel = { scrubMs = null }
                    ) { change, _ -> seekToX(change.position.x) }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(50))
                    .background(TRACK_COLOUR)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(height)
                        .clip(RoundedCornerShape(50))
                        .background(if (focused || scrubMs != null) KanalColors.Accent else KanalColors.AccentDim)
                )
            }

            // The handle only appears once the bar can be moved, so a passive
            // progress bar does not invite a press that does nothing.
            if (focused || scrubMs != null) {
                Box(
                    Modifier
                        .padding(start = barWidth * fraction)
                        .offset(x = (-7).dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(KanalColors.Accent)
                        .align(Alignment.CenterStart)
                )
            }
        }
    }
}

/** Unfilled part of the track, dark enough to read over any picture. */
private val TRACK_COLOUR = Color(0x59000000)

/** How long after the last key-up a scrub is committed; more presses in between add to it. */
const val SEEK_SETTLE_MS = 700L

/**
 * How far one key-down of a seek moves.
 *
 * A press is ten seconds. Holding moves at a speed that grows with how long the
 * key has been down, and the speed is per second held rather than per repeat:
 * repeats arrive every 50 ms on one remote and every 100 on another, and the
 * old step, which grew by half on every repeat, reached two minutes a repeat
 * within half a second and crossed a film before the user could let go.
 */
class HeldSeek {
    private var pressed = false
    private var downAt = 0L
    private var lastAt = 0L

    /** [repeat] is the key event's repeat count: zero for a fresh press. */
    fun step(repeat: Int, now: Long = System.currentTimeMillis()): Long {
        if (repeat == 0 || !pressed) {
            pressed = true
            downAt = now
            lastAt = now
            return PRESS_STEP_MS
        }
        val elapsed = (now - lastAt).coerceIn(0L, MAX_GAP_MS)
        lastAt = now
        val speed = when (now - downAt) {
            in 0L until 1_500L -> 30L
            in 1_500L until 4_000L -> 90L
            in 4_000L until 8_000L -> 240L
            else -> 480L
        }
        return elapsed * speed
    }

    private companion object {
        const val PRESS_STEP_MS = 10_000L
        /** A repeat that took longer than this is not counted as time held. */
        const val MAX_GAP_MS = 600L
    }
}
