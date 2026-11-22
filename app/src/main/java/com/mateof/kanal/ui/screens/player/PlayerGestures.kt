package com.mateof.kanal.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BrightnessHigh
import androidx.compose.material.icons.outlined.BrightnessLow
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.VolumeDown
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mateof.kanal.R
import com.mateof.kanal.ui.theme.KanalColors
import kotlin.math.abs

/** What a finger dragged across the picture is about to do. */
enum class PlayerGesture { Back, Fullscreen, Pip, Cast }

/** Where on the picture a drag started. The sides are dials, the middle is for swipes. */
enum class TouchZone { Left, Centre, Right }

/** What a vertical drag on one side of the picture is turning. */
enum class PlayerLevel { Brightness, Volume }

/**
 * A finger has no labels on it. The picture follows the drag and says what
 * letting go would do, so a gesture can be tried out and taken back — before
 * this, a half-swipe either did nothing or jumped somewhere unannounced.
 */
object PlayerGestures {
    /** Below this the drag is still a wobble on a tap, and nothing is shown. */
    val HintTravelDp = 28.dp

    /** Dragging this share of the picture's height runs a dial from end to end. */
    const val LevelTravelShare = 0.6f

    /**
     * The gesture a drag is heading for, or null when this device cannot do it.
     * Decided by the dominant axis, so a diagonal drag still commits to one
     * thing rather than flickering between two.
     */
    fun pending(
        travel: Offset,
        canGoFullscreen: Boolean,
        canPip: Boolean,
        fingers: Int = 1
    ): PlayerGesture? = when {
        travel == Offset.Zero -> null
        // Three fingers only ever mean one thing, so sideways or down is nothing.
        fingers >= 3 -> PlayerGesture.Cast.takeIf { travel.y < 0f && abs(travel.y) > abs(travel.x) }
        fingers != 1 -> null
        abs(travel.x) > abs(travel.y) -> PlayerGesture.Back
        travel.y < 0f -> PlayerGesture.Fullscreen.takeIf { canGoFullscreen }
        else -> PlayerGesture.Pip.takeIf { canPip }
    }

    /** How far along its threshold the drag is, 0..1. */
    fun progress(travel: Offset, gesture: PlayerGesture?, threshold: Float): Float {
        if (gesture == null || threshold <= 0f) return 0f
        val reach = if (gesture == PlayerGesture.Back) abs(travel.x) else abs(travel.y)
        // Three fingers drift apart on the way up; a shorter run is asked of them.
        val needed = if (gesture == PlayerGesture.Cast) threshold * 0.8f else threshold
        return (reach / needed).coerceIn(0f, 1f)
    }

    /**
     * Thirds, not halves: halves would leave no room for the vertical swipes
     * that already meant full screen and small window.
     */
    fun zoneOf(x: Float, width: Int): TouchZone = when {
        width <= 0 -> TouchZone.Centre
        x < width / 3f -> TouchZone.Left
        x > width * 2f / 3f -> TouchZone.Right
        else -> TouchZone.Centre
    }

    /**
     * The level a dial reaches after [dy] pixels of drag (negative = up), from
     * [start]. Measured from where the dial was picked up rather than added step
     * by step, so going back to the starting point gives the starting level.
     */
    fun level(start: Float, dy: Float, height: Int): Float {
        if (height <= 0) return start.coerceIn(0f, 1f)
        return (start - dy / (height * LevelTravelShare)).coerceIn(0f, 1f)
    }
}

/**
 * What the picture does while the finger is down. Damped on purpose: the video
 * follows at about half speed and stops where the gesture fires, so the screen
 * never looks like it is being thrown off the edge.
 */
data class GestureTransform(
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val scale: Float = 1f,
    val alpha: Float = 1f
)

fun gestureTransform(
    travel: Offset,
    gesture: PlayerGesture?,
    threshold: Float
): GestureTransform {
    if (gesture == null) return GestureTransform()
    val progress = PlayerGestures.progress(travel, gesture, threshold)
    return when (gesture) {
        // Slides after the finger and fades a little: the screen is on its way out.
        PlayerGesture.Back -> GestureTransform(
            translationX = travel.x.coerceIn(-threshold, threshold) * 0.5f,
            scale = 1f - 0.06f * progress,
            alpha = 1f - 0.25f * progress
        )
        // Grows towards filling everything.
        PlayerGesture.Fullscreen -> GestureTransform(
            translationY = travel.y.coerceIn(-threshold, 0f) * 0.25f,
            scale = 1f + 0.05f * progress
        )
        // Shrinks towards the little window it is about to become.
        PlayerGesture.Pip -> GestureTransform(
            translationY = travel.y.coerceIn(0f, threshold) * 0.35f,
            scale = 1f - 0.18f * progress,
            alpha = 1f - 0.15f * progress
        )
        // Lifts away, towards the television it is about to go to.
        PlayerGesture.Cast -> GestureTransform(
            translationY = travel.y.coerceIn(-threshold, 0f) * 0.35f,
            scale = 1f - 0.1f * progress,
            alpha = 1f - 0.2f * progress
        )
    }
}

/** The badge over the picture naming the gesture, lit once it would fire. */
@Composable
fun GestureHint(
    gesture: PlayerGesture?,
    armed: Boolean,
    modifier: Modifier = Modifier
) {
    val lit by animateFloatAsState(
        targetValue = if (armed) 1f else 0f,
        animationSpec = spring(),
        label = "gestureArmed"
    )
    AnimatedVisibility(
        visible = gesture != null,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.85f),
        modifier = modifier
    ) {
        // Held so the badge does not blank out mid-fade when the finger lifts.
        val shown = gesture ?: PlayerGesture.Back
        val tint = androidx.compose.ui.graphics.lerp(KanalColors.OnSurfaceMuted, KanalColors.Accent, lit)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .graphicsLayer {
                    val grow = 1f + 0.08f * lit
                    scaleX = grow
                    scaleY = grow
                }
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xE605070C))
                .border(1.dp, tint.copy(alpha = 0.25f + 0.55f * lit), RoundedCornerShape(28.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Icon(
                imageVector = when (shown) {
                    PlayerGesture.Back -> Icons.Outlined.ArrowBack
                    PlayerGesture.Fullscreen -> Icons.Outlined.Fullscreen
                    PlayerGesture.Pip -> Icons.Outlined.PictureInPictureAlt
                    PlayerGesture.Cast -> Icons.Outlined.Cast
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = stringResource(
                    when (shown) {
                        PlayerGesture.Back -> R.string.common_back
                        PlayerGesture.Fullscreen -> R.string.player_fullscreen
                        PlayerGesture.Pip -> R.string.player_pip
                        PlayerGesture.Cast -> R.string.cast_send
                    }
                ),
                style = MaterialTheme.typography.titleSmall,
                color = tint
            )
        }
    }
}

/** A dial being turned, and where it stands, 0..1. */
data class LevelReading(val level: PlayerLevel, val value: Float)

/**
 * The column on the side being dragged. Drawn by the app rather than left to the
 * system's volume panel, which would cover the picture and say nothing about
 * brightness.
 */
@Composable
fun LevelIndicator(reading: LevelReading?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = reading != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        // Held so the column does not blank out mid-fade.
        val shown = reading ?: LevelReading(PlayerLevel.Volume, 0f)
        val fill by animateFloatAsState(shown.value, spring(stiffness = 1500f), label = "level")
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xE605070C))
                .padding(horizontal = 12.dp, vertical = 16.dp)
        ) {
            Icon(
                imageVector = when (shown.level) {
                    PlayerLevel.Brightness ->
                        if (shown.value < 0.5f) Icons.Outlined.BrightnessLow else Icons.Outlined.BrightnessHigh
                    PlayerLevel.Volume -> when {
                        shown.value <= 0f -> Icons.Outlined.VolumeOff
                        shown.value < 0.5f -> Icons.Outlined.VolumeDown
                        else -> Icons.Outlined.VolumeUp
                    }
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier
                    .width(6.dp)
                    .height(140.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0x33FFFFFF))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fill.coerceIn(0f, 1f))
                        .background(KanalColors.Accent)
                )
            }
            Text(
                text = "${(shown.value * 100).toInt()}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
        }
    }
}
