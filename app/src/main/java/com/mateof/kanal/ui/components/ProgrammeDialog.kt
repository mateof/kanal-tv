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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.mateof.kanal.R
import com.mateof.kanal.core.formatClock
import com.mateof.kanal.core.formatDay
import com.mateof.kanal.core.formatDuration
import com.mateof.kanal.data.db.EpgEntity
import com.mateof.kanal.ui.isCompact
import com.mateof.kanal.ui.theme.KanalColors
import kotlinx.coroutines.delay

/** Everything the programme sheet needs, gathered by whoever opens it. */
data class ProgrammeDetail(
    val programme: EpgEntity,
    val channelName: String,
    val channelLogo: String = "",
    val channelStreamId: String = "",
    val canReplay: Boolean = false
)

/**
 * Full description and details of one programme.
 *
 * A modal rather than an expanding row: the guide is a dense grid and growing a
 * cell in place would shove everything else around. Drawn as an overlay inside
 * the screen instead of a platform Dialog so it plays by the same focus rules
 * as the rest of the app.
 */
@Composable
fun ProgrammeDialog(
    detail: ProgrammeDetail,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onGoToChannel: (() -> Unit)? = null,
    onReplay: (() -> Unit)? = null
) {
    val focus = remember { FocusRequester() }
    val programme = detail.programme
    val nowMillis = System.currentTimeMillis()
    val isLive = programme.start <= nowMillis && programme.stop > nowMillis

    BackHandler { onDismiss() }

    LaunchedEffect(programme.start) {
        repeat(12) {
            if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xE0040609))
            // Tap-to-dismiss with pointerInput, never clickable: clickable adds a
            // focus target, and a full-screen one swallows every arrow press,
            // leaving the sheet's own buttons unreachable from a remote.
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (isCompact) 560.dp else 760.dp)
                .fillMaxWidth(if (isCompact) 0.94f else 0.7f)
                .heightIn(max = if (isCompact) 620.dp else 520.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xFF16203A),
                        1f to Color(0xFF0B1120)
                    )
                )
                .padding(if (isCompact) 22.dp else 32.dp)
                .focusGroup()
        ) {
            // --- Channel line -------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (detail.channelLogo.isNotBlank()) {
                    Box(
                        Modifier
                            .size(56.dp, 34.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x22FFFFFF))
                    ) {
                        ArtworkImage(
                            url = detail.channelLogo,
                            label = detail.channelName,
                            fallbackIcon = Icons.Outlined.LiveTv,
                            contentScale = ContentScale.Fit,
                            padding = 4.dp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    detail.channelName,
                    style = MaterialTheme.typography.labelLarge,
                    color = KanalColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isLive) {
                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(KanalColors.Live)
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            stringResource(R.string.common_live_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                programme.title,
                style = if (isCompact) {
                    MaterialTheme.typography.headlineSmall
                } else {
                    MaterialTheme.typography.headlineMedium
                },
                color = KanalColors.OnBackground
            )

            Spacer(Modifier.height(10.dp))
            Text(
                buildDetails(programme, nowMillis),
                style = MaterialTheme.typography.labelLarge,
                color = KanalColors.Accent
            )

            if (isLive) {
                Spacer(Modifier.height(12.dp))
                val total = (programme.stop - programme.start).toFloat()
                val elapsed = (nowMillis - programme.start).toFloat()
                ThinProgress(if (total > 0) elapsed / total else 0f, Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    programme.description.ifBlank {
                        stringResource(R.string.programme_no_description)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (programme.description.isBlank()) {
                        KanalColors.OnSurfaceFaint
                    } else {
                        KanalColors.OnSurfaceMuted
                    }
                )
            }

            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onGoToChannel != null) {
                    KanalButton(
                        text = stringResource(R.string.programme_watch_channel),
                        onClick = onGoToChannel,
                        icon = Icons.Outlined.PlayArrow,
                        tone = ButtonTone.Primary
                    )
                }
                if (detail.canReplay && onReplay != null) {
                    KanalButton(stringResource(R.string.guide_replay), onReplay, icon = Icons.Outlined.Replay)
                }
                KanalButton(stringResource(R.string.common_close), onDismiss, modifier = Modifier.focusRequester(focus))
            }
        }
    }
}

/** "Hoxe · 23:00 – 00:00 · 1 h · quedan 24 min · Cine". */
@Composable
private fun buildDetails(programme: EpgEntity, nowMillis: Long): String {
    val parts = mutableListOf<String>()
    parts += formatDay(programme.start)
    parts += "${formatClock(programme.start)} – ${formatClock(programme.stop)}"

    val length = programme.stop - programme.start
    if (length > 0) parts += formatDuration(length)

    when {
        programme.start > nowMillis ->
            parts += stringResource(R.string.programme_starts_in, formatDuration(programme.start - nowMillis))

        programme.stop > nowMillis ->
            parts += stringResource(R.string.programme_remaining, formatDuration(programme.stop - nowMillis))

        else -> parts += stringResource(R.string.programme_finished)
    }

    if (programme.category.isNotBlank()) parts += programme.category
    return parts.joinToString("  ·  ")
}
