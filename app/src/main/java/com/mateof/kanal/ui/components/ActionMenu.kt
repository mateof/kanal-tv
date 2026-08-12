package com.mateof.kanal.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mateof.kanal.ui.isCompact
import com.mateof.kanal.ui.theme.KanalColors
import kotlinx.coroutines.delay

/** One entry of an [ActionMenu]. */
data class MenuAction(
    val label: String,
    val icon: ImageVector,
    /** Marks an entry whose effect is already in force, such as a favourite. */
    val active: Boolean = false,
    val onClick: () -> Unit
)

/**
 * The things that can be done to whatever the menu was opened on.
 *
 * One component serves both the lists and the player, and both input methods:
 * a plain overlay of real focusable rows rather than a platform popup, so the
 * D-pad walks it exactly like every other list in the app and a finger can tap
 * the darkened area outside to dismiss it.
 *
 * Entries are spelled out in words. A bar sitting over the picture has to drop
 * its labels to fit, and the name of an action only helps if it is on screen at
 * the moment the user is looking for it.
 *
 * Actions are not dismissed automatically: some finish the job and should close
 * the menu, others — cycling the picture mode, or turning a favourite on and
 * seeing the entry change — are worth staying open for. Each one decides.
 */
@Composable
fun ActionMenu(
    title: String,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
    subtitle: String? = null
) {
    if (actions.isEmpty()) return

    val focus = remember { FocusRequester() }

    BackHandler { onDismiss() }

    // The rows arrive with the same frame that opens the menu, so the first of
    // them may not be attached yet; keep trying rather than leaving the remote
    // with nothing focused and no way back in.
    LaunchedEffect(Unit) {
        repeat(12) {
            if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xC0040609))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                // Fraction first, cap second. The other way round the fraction
                // is taken of the cap rather than of the screen, which on a
                // phone turned sideways left a 230 dp card cutting every label
                // in half.
                .fillMaxWidth(if (isCompact) 0.92f else 0.5f)
                .widthIn(max = 460.dp)
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(0f to Color(0xFF16203A), 1f to Color(0xFF0B1120))
                )
                // A tap on the card is not a tap outside it, and must not reach
                // the scrim behind and close what was just opened.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(if (isCompact) 20.dp else 26.dp)
                .focusGroup()
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = KanalColors.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.takeIf { it.isNotBlank() }?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = KanalColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(16.dp))

            // Scrolls rather than being a lazy list: a menu is a handful of
            // entries, and a LazyColumn with no bounded height of its own would
            // stretch the card to its maximum whatever it holds.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                actions.forEachIndexed { index, action ->
                    ActionRow(
                        action = action,
                        modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(action: MenuAction, modifier: Modifier = Modifier) {
    FocusableSurface(
        onClick = action.onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = KanalColors.Surface,
        focusedColor = KanalColors.Accent,
        focusedScale = 1.0f
    ) { focused ->
        val content = when {
            focused -> Color(0xFF06231F)
            action.active -> KanalColors.Accent
            else -> KanalColors.OnBackground
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                action.icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(14.dp))
            Text(
                action.label,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
