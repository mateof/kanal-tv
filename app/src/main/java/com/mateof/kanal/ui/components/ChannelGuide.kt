package com.mateof.kanal.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mateof.kanal.core.formatClock
import com.mateof.kanal.core.formatDayShort
import com.mateof.kanal.data.db.EpgEntity
import com.mateof.kanal.ui.theme.KanalColors
import java.util.Calendar

/**
 * A long programme title will not fit on one line at 10 feet, and cutting it
 * with an ellipsis hides exactly the part that says what it is. Scroll it
 * instead, and only when it actually overflows.
 */
fun Modifier.scrollingTitle(enabled: Boolean = true): Modifier =
    if (enabled) this.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_500) else this

/** Day tabs + the schedule of the selected day for a single channel. */
@Composable
fun ChannelGuide(
    days: List<Long>,
    selectedDay: Long,
    programmes: List<EpgEntity>,
    modifier: Modifier = Modifier,
    archiveAvailable: Boolean = false,
    emptyMessage: String = "No hay guía para este canal.",
    onSelectDay: (Long) -> Unit,
    /** Opening the sheet is the default action; replaying lives inside it. */
    onProgrammeClick: (EpgEntity) -> Unit = {}
) {
    val listState = rememberLazyListState()
    val nowMillis = System.currentTimeMillis()

    // Open on whatever is on air rather than at midnight.
    LaunchedEffect(selectedDay, programmes.size) {
        val index = programmes.indexOfFirst { it.stop > nowMillis }
        if (index > 0) listState.scrollToItem(index)
    }

    Column(modifier) {
        if (days.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(days) { day ->
                    KanalChip(
                        label = dayLabel(day),
                        selected = day == selectedDay,
                        onClick = { onSelectDay(day) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (programmes.isEmpty()) {
            Text(
                emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = KanalColors.OnSurfaceFaint,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            return@Column
        }

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(programmes, key = { it.start }) { programme ->
                GuideEntry(
                    programme = programme,
                    canReplay = archiveAvailable && programme.stop < nowMillis,
                    onClick = { onProgrammeClick(programme) }
                )
            }
        }
    }
}

@Composable
private fun GuideEntry(
    programme: EpgEntity,
    canReplay: Boolean,
    onClick: () -> Unit
) {
    val nowMillis = System.currentTimeMillis()
    val isLive = programme.start <= nowMillis && programme.stop > nowMillis

    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (isLive) KanalColors.SurfaceVariant else Color.Transparent,
        focusedColor = KanalColors.Accent,
        focusedScale = 1.0f
    ) { focused ->
        val contentColor = when {
            focused -> Color(0xFF06231F)
            isLive -> KanalColors.OnBackground
            else -> KanalColors.OnSurfaceMuted
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column(Modifier.width(62.dp)) {
                Text(
                    formatClock(programme.start),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (focused) contentColor else if (isLive) KanalColors.Live else KanalColors.OnSurfaceFaint
                )
                Text(
                    formatClock(programme.stop),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (focused) contentColor else KanalColors.OnSurfaceFaint
                )
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLive) {
                        Text(
                            "AHORA  ",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (focused) contentColor else KanalColors.Live
                        )
                    }
                    Text(
                        programme.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (programme.description.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        programme.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (focused) contentColor else KanalColors.OnSurfaceFaint,
                        maxLines = if (focused || isLive) 4 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (canReplay) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Outlined.Replay,
                    contentDescription = "Ver repetición",
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** "Hoy" / "Mañana" / "jueves 31 de julio". */
fun dayLabel(dayStart: Long): String {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when (dayStart) {
        today -> "Hoy"
        today + 86_400_000L -> "Mañana"
        else -> formatDayShort(dayStart)
    }
}
