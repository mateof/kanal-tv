package com.mateof.kanal.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mateof.kanal.ui.theme.KanalColors

/**
 * The one clickable primitive the whole app is built on.
 *
 * Compose Foundation's `clickable` already answers both the D-pad centre key
 * and a finger tap, so this only adds what a TV needs on top: a visible focus
 * state, a scale that lifts the item above its neighbours, and a hook telling
 * the screen which item is focused (used for previews and detail panes).
 */
@Composable
fun FocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    color: Color = KanalColors.Surface,
    focusedColor: Color = KanalColors.SurfaceVariant,
    focusBorderColor: Color = KanalColors.Accent,
    restingBorderColor: Color = Color.Transparent,
    focusedScale: Float = 1.06f,
    enabled: Boolean = true,
    onFocusState: (Boolean) -> Unit = {},
    /** Extra actions for this item; null leaves the long press doing nothing. */
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val notifyFocus by rememberUpdatedState(onFocusState)
    val interactionSource = remember { MutableInteractionSource() }

    // A finger produces no focus, so without this a tap looks like nothing
    // happened until the action itself lands. The ripple is deliberately not
    // used: it would fight the focus highlight on a television.
    val pressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> focusedScale * 0.96f
            focused -> focusedScale
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 900f),
        label = "surfaceScale"
    )
    val background by animateColorAsState(
        targetValue = when {
            focused -> focusedColor
            pressed -> focusedColor.copy(alpha = 0.45f).compositeOver(color)
            else -> color
        },
        animationSpec = tween(120),
        label = "surfaceBackground"
    )

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { state ->
                if (focused != state.isFocused) {
                    focused = state.isFocused
                    notifyFocus(state.isFocused)
                }
            }
            .clip(shape)
            .background(background)
            .border(
                BorderStroke(
                    width = if (focused || pressed) 3.dp else 1.dp,
                    color = if (focused || pressed) focusBorderColor else restingBorderColor
                ),
                shape
            )
            // combinedClickable rather than clickable so a held OK, or a long
            // press of the finger, can offer the extra actions without adding a
            // second focusable target beside every row.
            .combinedClickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick
            )
    ) {
        content(focused)
    }
}

/** Pill used for categories, filters and quick actions. */
@Composable
fun KanalChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusState: (Boolean) -> Unit = {}
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (selected) KanalColors.AccentDim else KanalColors.Surface,
        focusedColor = KanalColors.Accent,
        restingBorderColor = if (selected) KanalColors.Accent else KanalColors.Outline,
        focusedScale = 1.04f,
        onFocusState = onFocusState
    ) { focused ->
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = when {
                focused -> Color(0xFF06231F)
                selected -> Color.White
                else -> KanalColors.OnSurfaceMuted
            },
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
}
