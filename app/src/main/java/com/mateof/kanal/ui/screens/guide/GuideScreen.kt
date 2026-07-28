package com.mateof.kanal.ui.screens.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mateof.kanal.core.formatClock
import com.mateof.kanal.data.db.ChannelEntity
import com.mateof.kanal.data.db.EpgEntity
import com.mateof.kanal.ui.components.ArtworkImage
import com.mateof.kanal.ui.components.FocusableSurface
import com.mateof.kanal.ui.components.KanalButton
import com.mateof.kanal.ui.components.KanalChip
import com.mateof.kanal.ui.components.LoadingState
import com.mateof.kanal.ui.components.MessageState
import com.mateof.kanal.ui.components.scrollingTitle
import com.mateof.kanal.ui.isCompact
import com.mateof.kanal.ui.theme.KanalColors

/**
 * The classic guide wall: channels down the side, time across the top, one
 * block per programme sized to its length.
 *
 * Everything is laid out from a single "dp per minute" scale, so the blocks and
 * the ruler above them cannot drift apart. The whole grid shares one horizontal
 * scroll state for the same reason.
 */
@Composable
fun GuideScreen(onPlay: (String) -> Unit) {
    val vm: GuideViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val category by vm.category.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()

    val compact = isCompact
    val minuteWidth: Dp = if (compact) 2.4.dp else 4.dp
    val channelColumn: Dp = if (compact) 108.dp else 190.dp
    val rowHeight: Dp = if (compact) 62.dp else 72.dp

    val horizontal = rememberScrollState()
    val totalMinutes = ((state.windowEnd - state.windowStart) / 60_000L).toInt().coerceAtLeast(1)

    Column(Modifier.fillMaxSize()) {
        // --- Header: title, window controls, categories -----------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 24.dp, top = 20.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Guía", style = MaterialTheme.typography.headlineSmall, color = KanalColors.OnBackground)
                Text(
                    if (state.rows.isEmpty()) "" else {
                        "${state.rows.size} canales" + if (state.truncated) " (primeros ${GuideViewModel.CHANNEL_LIMIT})" else ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = KanalColors.OnSurfaceFaint
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KanalButton("−2 h", { vm.shiftWindow(-2) }, icon = Icons.Outlined.ChevronLeft)
                KanalButton("Ahora", vm::resetToNow)
                KanalButton("+2 h", { vm.shiftWindow(2) }, icon = Icons.Outlined.ChevronRight)
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                KanalChip(label = "Todos", selected = category.isEmpty(), onClick = { vm.selectCategory("") })
            }
            items(categories, key = { it.categoryId }) { item ->
                KanalChip(
                    label = item.name,
                    selected = item.categoryId == category,
                    onClick = { vm.selectCategory(item.categoryId) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        when {
            state.loading -> LoadingState("Montando la guía…")

            state.rows.isEmpty() -> MessageState(
                title = "No hay canales",
                description = "Sincroniza la fuente desde Ajustes.",
                icon = Icons.Outlined.LiveTv
            )

            !state.hasGuide -> MessageState(
                title = "Sin guía en esta franja",
                description = "El proveedor no ha enviado programación para estos canales. " +
                    "Prueba a actualizar la guía desde Ajustes.",
                icon = Icons.Outlined.LiveTv
            )

            else -> {
                // --- Time ruler ------------------------------------------------
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(channelColumn + 16.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .horizontalScroll(horizontal)
                    ) {
                        TimeRuler(
                            start = state.windowStart,
                            totalMinutes = totalMinutes,
                            minuteWidth = minuteWidth
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))

                // --- Rows ------------------------------------------------------
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(state.rows, key = { it.channel.streamId }) { row ->
                        Row(Modifier.height(rowHeight)) {
                            ChannelLabel(
                                channel = row.channel,
                                width = channelColumn,
                                onClick = { onPlay(row.channel.streamId) }
                            )
                            Spacer(Modifier.width(16.dp))
                            Box(
                                Modifier
                                    .weight(1f)
                                    .horizontalScroll(horizontal)
                            ) {
                                ProgrammeStrip(
                                    programmes = row.programmes,
                                    windowStart = state.windowStart,
                                    totalMinutes = totalMinutes,
                                    minuteWidth = minuteWidth,
                                    onFocused = { vm.onProgrammeFocused(row.channel, it) },
                                    onClick = { onPlay(row.channel.streamId) }
                                )
                            }
                        }
                    }
                }

                // --- Detail of whatever has focus ------------------------------
                selected?.let { (channel, programme) ->
                    SelectionDetail(channel, programme, compact)
                }
            }
        }
    }
}

@Composable
private fun TimeRuler(start: Long, totalMinutes: Int, minuteWidth: Dp) {
    val halfHours = totalMinutes / 30
    Row(Modifier.width(minuteWidth * totalMinutes)) {
        repeat(halfHours) { index ->
            val at = start + index * 30 * 60_000L
            Box(
                Modifier
                    .width(minuteWidth * 30)
                    .height(24.dp)
            ) {
                Text(
                    formatClock(at),
                    style = MaterialTheme.typography.labelSmall,
                    color = KanalColors.OnSurfaceFaint,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ChannelLabel(channel: ChannelEntity, width: Dp, onClick: () -> Unit) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier
            .width(width)
            .fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = KanalColors.Surface,
        focusedColor = KanalColors.Accent,
        focusedScale = 1.0f
    ) { focused ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp, 28.dp)
                    .clip(RoundedCornerShape(6.dp))
            ) {
                ArtworkImage(
                    url = channel.logo,
                    label = channel.name,
                    fallbackIcon = Icons.Outlined.LiveTv,
                    contentScale = ContentScale.Fit
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                channel.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (focused) Color(0xFF06231F) else KanalColors.OnBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ProgrammeStrip(
    programmes: List<EpgEntity>,
    windowStart: Long,
    totalMinutes: Int,
    minuteWidth: Dp,
    onFocused: (EpgEntity) -> Unit,
    onClick: () -> Unit
) {
    if (programmes.isEmpty()) {
        Box(
            Modifier
                .width(minuteWidth * totalMinutes)
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(KanalColors.Surface),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                "  Sin guía",
                style = MaterialTheme.typography.labelSmall,
                color = KanalColors.OnSurfaceFaint
            )
        }
        return
    }

    // Blocks are laid out edge to edge; a gap in the guide becomes a spacer of
    // exactly the missing minutes, which keeps every row aligned to the ruler.
    Row(Modifier.width(minuteWidth * totalMinutes)) {
        var cursor = windowStart
        programmes.forEach { programme ->
            val gapMinutes = ((programme.start - cursor) / 60_000L).toInt()
            if (gapMinutes > 0) Spacer(Modifier.width(minuteWidth * gapMinutes))

            val from = maxOf(programme.start, windowStart)
            val minutes = ((programme.stop - from) / 60_000L).toInt().coerceIn(1, totalMinutes)
            ProgrammeBlock(
                programme = programme,
                width = minuteWidth * minutes,
                onFocused = { onFocused(programme) },
                onClick = onClick
            )
            cursor = programme.stop
        }
    }
}

@Composable
private fun ProgrammeBlock(
    programme: EpgEntity,
    width: Dp,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    val nowMillis = System.currentTimeMillis()
    val isLive = programme.start <= nowMillis && programme.stop > nowMillis

    FocusableSurface(
        onClick = onClick,
        modifier = Modifier
            .width(width)
            .fillMaxSize()
            .padding(end = 2.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (isLive) KanalColors.SurfaceVariant else KanalColors.Surface,
        focusedColor = KanalColors.Accent,
        focusedScale = 1.0f,
        onFocusState = { if (it) onFocused() }
    ) { focused ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                programme.title,
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    focused -> Color(0xFF06231F)
                    isLive -> KanalColors.OnBackground
                    else -> KanalColors.OnSurfaceMuted
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                formatClock(programme.start),
                style = MaterialTheme.typography.labelSmall,
                color = if (focused) Color(0xFF0B3E37) else KanalColors.OnSurfaceFaint,
                maxLines = 1
            )
        }
    }
}

/** Bottom strip describing whatever block currently has focus. */
@Composable
private fun SelectionDetail(channel: ChannelEntity, programme: EpgEntity, compact: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(KanalColors.BackgroundElevated)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(
            "${channel.name} · ${formatClock(programme.start)} – ${formatClock(programme.stop)}",
            style = MaterialTheme.typography.labelMedium,
            color = KanalColors.Accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            programme.title,
            style = MaterialTheme.typography.titleMedium,
            color = KanalColors.OnBackground,
            maxLines = 1,
            modifier = Modifier.scrollingTitle()
        )
        if (programme.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                programme.description,
                style = MaterialTheme.typography.bodySmall,
                color = KanalColors.OnSurfaceMuted,
                maxLines = if (compact) 2 else 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
