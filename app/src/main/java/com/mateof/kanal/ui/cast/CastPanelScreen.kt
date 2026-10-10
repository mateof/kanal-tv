package com.mateof.kanal.ui.cast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mateof.kanal.R
import com.mateof.kanal.cast.CastController
import com.mateof.kanal.cast.CastIncident
import com.mateof.kanal.cast.CastPhase
import com.mateof.kanal.cast.CastSession
import com.mateof.kanal.core.formatBitrate
import com.mateof.kanal.core.formatBytes
import com.mateof.kanal.core.formatClock
import com.mateof.kanal.core.formatDuration
import com.mateof.kanal.core.resolve
import com.mateof.kanal.data.db.EpgEntity
import com.mateof.kanal.ui.components.ButtonTone
import com.mateof.kanal.ui.components.KanalButton
import com.mateof.kanal.ui.isCompact
import com.mateof.kanal.ui.screenPadding
import com.mateof.kanal.ui.theme.KanalColors
import kotlinx.coroutines.delay
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything about the send in progress: how fast the television is being fed,
 * what it says it is doing, the route, what went wrong, and the buttons to
 * stop it, send it again or take it back.
 */
@Composable
fun CastPanelScreen(
    onBack: () -> Unit,
    onWatchHere: (kind: String, itemId: String) -> Unit
) {
    val vm: CastPanelViewModel = hiltViewModel()
    val session by vm.session.collectAsStateWithLifecycle()
    val programme by vm.programme.collectAsStateWithLifecycle()

    BackHandler { onBack() }

    DisposableEffect(Unit) {
        vm.visible(true)
        onDispose { vm.visible(false) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(KanalColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(start = screenPadding, end = screenPadding, top = 28.dp, bottom = 24.dp)
    ) {
        Text(
            stringResource(R.string.cast_panel_title),
            style = MaterialTheme.typography.headlineMedium,
            color = KanalColors.OnBackground
        )
        Spacer(Modifier.height(12.dp))

        val current = session
        if (current == null) {
            Text(
                stringResource(R.string.cast_panel_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = KanalColors.OnSurfaceMuted
            )
            Spacer(Modifier.height(20.dp))
            BackButton(onBack, focusFirst = true)
            return@Column
        }

        Header(current, programme)
        Spacer(Modifier.height(16.dp))
        Actions(
            session = current,
            onStop = vm::stop,
            onReconnect = vm::reconnect,
            onDismiss = vm::dismiss,
            onWatchHere = {
                val origin = current.request.origin ?: return@Actions
                vm.watchHere { onWatchHere(origin.kind.name, origin.itemId) }
            },
            onBack = onBack
        )
        Spacer(Modifier.height(20.dp))

        if (isCompact) {
            SpeedCard(current)
            Spacer(Modifier.height(14.dp))
            TvCard(current)
            Spacer(Modifier.height(14.dp))
            ConnectionCard(current)
            Spacer(Modifier.height(14.dp))
            EventsCard(current, limit = Int.MAX_VALUE)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(1f)) {
                    SpeedCard(current)
                    Spacer(Modifier.height(14.dp))
                    ConnectionCard(current)
                }
                Column(Modifier.weight(1f)) {
                    TvCard(current)
                    Spacer(Modifier.height(14.dp))
                    EventsCard(current, limit = WIDE_EVENTS)
                }
            }
        }
    }
}

@Composable
private fun Header(session: CastSession, programme: EpgEntity?) {
    val (label, color) = when (session.phase) {
        CastPhase.ACTIVE -> stringResource(R.string.cast_phase_active, session.device.name) to KanalColors.Accent
        CastPhase.RECONNECTING -> stringResource(R.string.cast_phase_reconnecting, session.device.name) to KanalColors.Warning
        CastPhase.ENDED -> stringResource(R.string.cast_phase_ended, session.device.name) to KanalColors.Error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = color)
    }
    Spacer(Modifier.height(6.dp))
    Text(
        session.request.title,
        style = MaterialTheme.typography.titleLarge,
        color = KanalColors.OnBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    val subtitle = programme?.let { "${it.title} · ${formatClock(it.start)}–${formatClock(it.stop)}" }
        ?: session.request.origin?.subtitle?.takeIf { it.isNotBlank() }
    subtitle?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = KanalColors.OnSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (session.phase == CastPhase.ENDED) {
        session.endHint?.let {
            Spacer(Modifier.height(8.dp))
            Text(it.resolve(), style = MaterialTheme.typography.bodyMedium, color = KanalColors.OnSurfaceMuted)
        }
        session.endProblem?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = KanalColors.OnSurfaceFaint)
        }
    }
}

@Composable
private fun Actions(
    session: CastSession,
    onStop: () -> Unit,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
    onWatchHere: () -> Unit,
    onBack: () -> Unit
) {
    val focus = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val diagnostics = diagnostics(session)

    LaunchedEffect(Unit) {
        repeat(12) {
            if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40)
        }
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_500)
            copied = false
        }
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val ended = session.phase == CastPhase.ENDED
        KanalButton(
            stringResource(R.string.cast_action_reconnect),
            onReconnect,
            modifier = if (ended) Modifier.focusRequester(focus) else Modifier,
            icon = Icons.Outlined.Refresh,
            tone = if (ended) ButtonTone.Primary else ButtonTone.Neutral,
            enabled = session.phase != CastPhase.RECONNECTING || session.attempt > 0
        )
        if (!ended) {
            KanalButton(
                stringResource(R.string.cast_action_stop),
                onStop,
                modifier = Modifier.focusRequester(focus),
                icon = Icons.Outlined.Stop,
                tone = ButtonTone.Danger
            )
        }
        if (session.request.origin != null) {
            KanalButton(stringResource(R.string.cast_action_watch_here), onWatchHere, icon = Icons.Outlined.PhoneAndroid)
        }
        KanalButton(
            stringResource(R.string.cast_action_copy),
            {
                clipboard.setText(AnnotatedString(diagnostics))
                copied = true
            },
            icon = Icons.Outlined.ContentCopy
        )
        if (ended) KanalButton(stringResource(R.string.cast_action_dismiss), onDismiss)
        BackButton(onBack)
    }
    if (copied) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.cast_copied), style = MaterialTheme.typography.labelMedium, color = KanalColors.Accent)
    }
    if (session.viaPhone && session.phase == CastPhase.ACTIVE) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.cast_reconnect_auto_hint, CastController.MAX_AUTO_ATTEMPTS),
            style = MaterialTheme.typography.labelSmall,
            color = KanalColors.OnSurfaceFaint
        )
    }
}

@Composable
private fun BackButton(onBack: () -> Unit, focusFirst: Boolean = false) {
    val focus = remember { FocusRequester() }
    if (focusFirst) {
        LaunchedEffect(Unit) {
            repeat(12) {
                if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(40)
            }
        }
    }
    KanalButton(
        stringResource(R.string.common_back),
        onBack,
        modifier = if (focusFirst) Modifier.focusRequester(focus) else Modifier
    )
}

@Composable
private fun SpeedCard(session: CastSession) {
    Card(stringResource(R.string.cast_section_speed)) {
        if (!session.viaPhone) {
            Text(
                stringResource(R.string.cast_speed_direct),
                style = MaterialTheme.typography.bodyMedium,
                color = KanalColors.OnSurfaceMuted
            )
            return@Card
        }
        val now by produceState(System.currentTimeMillis()) {
            while (true) {
                delay(1_000)
                value = System.currentTimeMillis()
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                formatBitrate(session.rateBytes),
                style = MaterialTheme.typography.headlineMedium,
                color = if (session.tvConnected) KanalColors.Accent else KanalColors.OnSurfaceMuted
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.cast_stat_current),
                style = MaterialTheme.typography.labelMedium,
                color = KanalColors.OnSurfaceFaint,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        if (session.history.size >= 2) {
            Sparkline(session.history, Modifier.fillMaxWidth().height(56.dp))
        } else {
            Text(
                stringResource(R.string.cast_speed_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = KanalColors.OnSurfaceFaint
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Figure(stringResource(R.string.cast_stat_average), formatBitrate(session.averageBytes))
            Figure(stringResource(R.string.cast_stat_served), formatBytes(session.servedBytes))
            Figure(stringResource(R.string.cast_stat_elapsed), formatDuration((session.endedAt ?: now) - session.startedAt).ifEmpty { "0 s" })
        }
    }
}

@Composable
private fun TvCard(session: CastSession) {
    Card(stringResource(R.string.cast_section_tv)) {
        Field(session.device.name, null)
        Field(stringResource(R.string.cast_tv_address), session.device.host)
        Field(stringResource(R.string.cast_tv_state), tvStateLabel(session.tvState))
        session.position?.let { position ->
            val text = listOfNotNull(position.elapsed, position.duration).joinToString(" / ")
            if (text.isNotEmpty()) Field(stringResource(R.string.cast_tv_position), text)
        }
        if (session.viaPhone) {
            Field(
                stringResource(R.string.cast_tv_link),
                stringResource(if (session.tvConnected) R.string.cast_tv_reading else R.string.cast_tv_not_reading)
            )
        }
        session.tvAgent?.let { Field(stringResource(R.string.cast_tv_player), it) }
    }
}

@Composable
private fun ConnectionCard(session: CastSession) {
    Card(stringResource(R.string.cast_section_connection)) {
        Field(stringResource(R.string.cast_conn_route), routeLabel(session))
        session.link?.let { link ->
            val phone = listOfNotNull(link.interfaceName, link.address + (link.port?.let { ":$it" } ?: ""))
            Field(stringResource(R.string.cast_conn_phone), phone.joinToString(" · "))
            Field(stringResource(R.string.cast_conn_network), link.subnet)
        }
        serverOf(session)?.let { Field(stringResource(R.string.cast_conn_server), it) }
        session.upstreamMs?.let { Field(stringResource(R.string.cast_conn_server_time), "$it ms") }
        if (session.viaPhone) {
            Field(stringResource(R.string.cast_conn_tv_comebacks), pluralStringResource(R.plurals.cast_times, session.tvComebacks, session.tvComebacks))
        }
        Field(stringResource(R.string.cast_conn_resends), pluralStringResource(R.plurals.cast_times, session.resends, session.resends))
    }
}

@Composable
private fun EventsCard(session: CastSession, limit: Int) {
    Card(stringResource(R.string.cast_section_events)) {
        if (session.incidents.isEmpty()) {
            Text(stringResource(R.string.cast_events_empty), style = MaterialTheme.typography.bodySmall, color = KanalColors.OnSurfaceFaint)
            return@Card
        }
        session.incidents.take(limit).forEach { incident ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(
                    timeOf(incident.at),
                    style = MaterialTheme.typography.labelSmall,
                    color = KanalColors.OnSurfaceFaint,
                    modifier = Modifier.width(64.dp).padding(top = 2.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        incident.text.resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (incident.level) {
                            CastIncident.Level.INFO -> KanalColors.OnBackground
                            CastIncident.Level.WARN -> KanalColors.Warning
                            CastIncident.Level.ERROR -> KanalColors.Error
                        }
                    )
                    incident.detail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = KanalColors.OnSurfaceFaint,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * A section of the panel. Focusable although there is nothing to press: on a
 * television the arrows only scroll by moving focus, and without a target
 * below the buttons the lower cards could never be reached with the remote.
 */
@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(KanalColors.Surface)
            .border(2.dp, if (focused) KanalColors.Accent else KanalColors.Surface, shape)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = KanalColors.OnSurfaceMuted)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun Field(label: String, value: String?) {
    Row(Modifier.padding(vertical = 2.dp)) {
        if (value == null) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = KanalColors.OnBackground)
            return@Row
        }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = KanalColors.OnSurfaceMuted,
            modifier = Modifier.width(if (isCompact) 120.dp else 170.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = KanalColors.OnBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun Figure(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall, color = KanalColors.OnBackground)
        Text(label, style = MaterialTheme.typography.labelSmall, color = KanalColors.OnSurfaceFaint)
    }
}

/** The last minute of speed, scaled to its own peak: the shape is what tells a stall from a dip. */
@Composable
private fun Sparkline(history: List<Long>, modifier: Modifier) {
    val line = KanalColors.Accent
    Canvas(modifier) {
        val peak = (history.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
        val step = size.width / (history.size - 1).coerceAtLeast(1)
        fun point(i: Int) = Offset(step * i, size.height - size.height * (history[i] / peak) * 0.9f)
        val stroke = Path().apply {
            moveTo(point(0).x, point(0).y)
            for (i in 1 until history.size) lineTo(point(i).x, point(i).y)
        }
        val fill = Path().apply {
            addPath(stroke)
            lineTo(point(history.lastIndex).x, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, line.copy(alpha = 0.16f))
        drawPath(stroke, line, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun tvStateLabel(state: String?): String = when (state) {
    null -> stringResource(R.string.cast_tv_state_unknown)
    "PLAYING" -> stringResource(R.string.cast_tv_state_playing)
    "TRANSITIONING" -> stringResource(R.string.cast_tv_state_transitioning)
    "PAUSED_PLAYBACK" -> stringResource(R.string.cast_tv_state_paused)
    "STOPPED" -> stringResource(R.string.cast_tv_state_stopped)
    "NO_MEDIA_PRESENT" -> stringResource(R.string.cast_tv_state_no_media)
    else -> state
}

@Composable
private fun routeLabel(session: CastSession): String = when {
    session.fallback -> stringResource(R.string.cast_route_fallback)
    session.viaPhone -> stringResource(R.string.cast_mode_relay)
    else -> stringResource(R.string.cast_mode_direct)
}

/** Host and port only: the path of a provider's url carries the account's credentials. */
private fun serverOf(session: CastSession): String? {
    val url = if (session.viaPhone) session.request.relayUrl else session.request.directUrl
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    return if (uri.port > 0) "$host:${uri.port}" else host
}

/** The same panel as text, for pasting into a message when asking for help. */
@Composable
private fun diagnostics(session: CastSession): String {
    val lines = mutableListOf<String>()
    lines += "Kanal · ${stringResource(R.string.cast_panel_title)}"
    lines += "${session.request.title} → ${session.device.name} (${session.device.host})"
    lines += "${stringResource(R.string.cast_conn_route)}: ${routeLabel(session)}"
    lines += "${stringResource(R.string.cast_tv_state)}: ${tvStateLabel(session.tvState)}"
    if (session.viaPhone) {
        lines += "${stringResource(R.string.cast_section_speed)}: ${formatBitrate(session.rateBytes)} · " +
            "${stringResource(R.string.cast_stat_average)} ${formatBitrate(session.averageBytes)} · " +
            "${stringResource(R.string.cast_stat_served)} ${formatBytes(session.servedBytes)}"
        session.link?.let { lines += "${stringResource(R.string.cast_conn_phone)}: ${it.interfaceName ?: "?"} ${it.address}:${it.port ?: "?"} (${it.subnet})" }
        session.tvAgent?.let { lines += "${stringResource(R.string.cast_tv_player)}: $it" }
        lines += "${stringResource(R.string.cast_conn_tv_comebacks)}: ${session.tvComebacks}"
    }
    serverOf(session)?.let { lines += "${stringResource(R.string.cast_conn_server)}: $it" + (session.upstreamMs?.let { ms -> " · $ms ms" } ?: "") }
    lines += "${stringResource(R.string.cast_conn_resends)}: ${session.resends}"
    session.endProblem?.let { lines += it }
    lines += ""
    lines += "${stringResource(R.string.cast_section_events)}:"
    session.incidents.forEach { incident ->
        lines += "${timeOf(incident.at)}  ${incident.text.resolve()}" + (incident.detail?.let { " — $it" } ?: "")
    }
    return lines.joinToString("\n")
}

private fun timeOf(epochMillis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(epochMillis))

/** Enough to read back a bad evening; the copied diagnostics keep them all. */
private const val WIDE_EVENTS = 12
