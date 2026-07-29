package com.mateof.kanal.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.mateof.kanal.core.formatClock
import com.mateof.kanal.core.formatDuration
import com.mateof.kanal.ui.components.ArtworkImage
import com.mateof.kanal.ui.components.ChannelGuide
import com.mateof.kanal.ui.components.ButtonTone
import com.mateof.kanal.ui.components.FocusableSurface
import com.mateof.kanal.ui.components.KanalButton
import com.mateof.kanal.ui.components.ProgrammeDetail
import com.mateof.kanal.ui.components.ProgrammeDialog
import com.mateof.kanal.ui.components.ThinProgress
import com.mateof.kanal.ui.components.scrollingTitle
import com.mateof.kanal.ui.isCompact
import com.mateof.kanal.ui.theme.KanalColors
import kotlinx.coroutines.delay

private const val OSD_TIMEOUT_MS = 6_000L

/**
 * Who owns the D-pad at any moment.
 *
 * The player has to serve two jobs with the same four arrow keys: driving
 * playback (zapping, seeking) and operating the on-screen controls. Mixing them
 * makes the buttons unreachable — the root box swallows every arrow, focus
 * never moves and, on a remote with no MENU key, there is no way in at all.
 * So the modes are explicit and only [Mode.Watching] consumes the arrows.
 */
private enum class Mode { Watching, Controls, Tracks, Guide }

@Composable
fun PlayerScreen(
    kind: String,
    itemId: String,
    startMillis: Long,
    /** Receives the live channel just watched, so the list can keep it playing. */
    onBack: (String?) -> Unit
) {
    val vm: PlayerViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    var mode by remember { mutableStateOf(Mode.Watching) }
    var osdVisible by remember { mutableStateOf(true) }
    var osdTick by remember { mutableIntStateOf(0) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    val stageFocus = remember { FocusRequester() }
    val controlsFocus = remember { FocusRequester() }
    val tracksFocus = remember { FocusRequester() }
    val guideFocus = remember { FocusRequester() }
    var detail by remember { mutableStateOf<ProgrammeDetail?>(null) }

    val isLive = state.playable?.isLive == true
    fun liveChannelId(): String? = state.playable?.takeIf { it.isLive }?.itemId

    fun poke() {
        osdVisible = true
        osdTick++
    }

    fun openControls() {
        osdVisible = true
        osdTick++
        mode = Mode.Controls
    }

    LaunchedEffect(kind, itemId, startMillis) { vm.load(kind, itemId, startMillis) }

    // Focus follows the mode, so the arrows always land where the user expects.
    // The control bar and the track panel appear with the same frame that
    // changes the mode, so their target may not be attached yet: keep trying
    // for a moment instead of silently leaving focus on the video.
    LaunchedEffect(mode) {
        val target = when (mode) {
            Mode.Watching -> stageFocus
            Mode.Controls -> controlsFocus
            Mode.Tracks -> tracksFocus
            Mode.Guide -> guideFocus
        }
        repeat(12) {
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40)
        }
    }

    // The bar only fades out while watching; it must stay while it is being used.
    LaunchedEffect(osdTick, mode) {
        if (mode != Mode.Watching) return@LaunchedEffect
        delay(OSD_TIMEOUT_MS)
        osdVisible = false
    }

    BackHandler(enabled = detail == null) {
        when (mode) {
            Mode.Tracks, Mode.Guide -> mode = Mode.Controls
            Mode.Controls -> mode = Mode.Watching
            // The title sitting over the picture has to go the moment BACK is
            // pressed, not on the next auto-hide tick.
            Mode.Watching -> if (osdVisible) osdVisible = false else onBack(liveChannelId())
        }
    }

    // Keep now/next honest across a programme changeover while the OSD is up.
    LaunchedEffect(isLive) {
        if (!isLive) return@LaunchedEffect
        while (true) {
            delay(60_000)
            vm.refreshNowNext()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // A finger has no OK key. Tapping the picture opens the controls and
            // tapping outside them puts it away again. The buttons are drawn on
            // top and consume their own taps, so they are unaffected.
            .pointerInput(Unit) {
                detectTapGestures {
                    if (mode == Mode.Watching) openControls() else mode = Mode.Watching
                }
            }
            .focusRequester(stageFocus)
            .focusable()
            .onKeyEvent { event ->
                // Outside Watching the arrows belong to Compose's focus system.
                if (mode != Mode.Watching) return@onKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        poke()
                        if (isLive) vm.zap(-1)
                        true
                    }

                    Key.DirectionDown -> {
                        poke()
                        if (isLive) vm.zap(1)
                        true
                    }

                    Key.DirectionLeft -> {
                        poke()
                        if (!isLive) vm.seekBy(-10_000)
                        true
                    }

                    Key.DirectionRight -> {
                        poke()
                        if (!isLive) vm.seekBy(10_000)
                        true
                    }

                    // The only way into the controls on a remote without MENU.
                    Key.DirectionCenter, Key.Enter -> {
                        openControls()
                        true
                    }

                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        vm.togglePlayPause(); poke(); true
                    }

                    Key.Menu, Key.Info -> {
                        openControls(); true
                    }

                    else -> false
                }
            }
    ) {
        val player = vm.player
        if (player != null) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        setKeepContentOnPlayerReset(true)
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        setPlayer(player)
                    }
                },
                update = { view ->
                    view.player = player
                    view.resizeMode = resizeMode
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (state.loading || (state.buffering && state.error.isBlank())) {
            CircularProgressIndicator(
                color = KanalColors.Accent,
                strokeWidth = 3.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(56.dp)
            )
        }

        if (state.error.isNotBlank()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(40.dp)
                    .focusGroup(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    state.error,
                    style = MaterialTheme.typography.titleLarge,
                    color = KanalColors.Error
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KanalButton("Reintentar", vm::retry, tone = ButtonTone.Primary)
                    KanalButton("Volver", { onBack(liveChannelId()) })
                }
            }
        }

        AnimatedVisibility(
            visible = osdVisible && state.error.isBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart)
        ) {
            Osd(
                state = state,
                controlsActive = mode == Mode.Controls,
                controlsFocus = controlsFocus,
                resizeLabel = resizeLabel(resizeMode),
                onCycleResize = {
                    resizeMode = nextResizeMode(resizeMode)
                    poke()
                },
                onTogglePlay = { vm.togglePlayPause(); poke() },
                onSeekBack = { vm.seekBy(-10_000); poke() },
                onSeekForward = { vm.seekBy(10_000); poke() },
                onOpenTracks = { mode = Mode.Tracks; poke() },
                onOpenGuide = { mode = Mode.Guide; poke() },
                onOpenDetail = {
                    val now = state.now ?: return@Osd
                    detail = ProgrammeDetail(
                        programme = now,
                        channelName = state.playable?.title.orEmpty(),
                        channelLogo = state.playable?.logo.orEmpty()
                    )
                }
            )
        }

        if (mode == Mode.Tracks) {
            TrackPanel(
                state = state,
                focusRequester = tracksFocus,
                onSelect = { option -> vm.selectTrack(option); poke() },
                onDisableSubtitles = { vm.disableSubtitles(); poke() },
                onClose = { mode = Mode.Controls },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }

        detail?.let { open ->
            ProgrammeDialog(detail = open, onDismiss = { detail = null; poke() })
        }

        if (mode == Mode.Guide) {
            GuidePanel(
                state = state,
                focusRequester = guideFocus,
                onSelectDay = { day -> vm.selectGuideDay(day); poke() },
                onProgrammeClick = { programme ->
                    detail = ProgrammeDetail(
                        programme = programme,
                        channelName = state.playable?.title.orEmpty(),
                        channelLogo = state.playable?.logo.orEmpty()
                    )
                },
                onClose = { mode = Mode.Controls },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

/**
 * The full guide of the channel being watched, day by day. Deliberately a panel
 * you open rather than something always on screen: the OSD already gives now
 * and next, and this is for when the user wants more.
 */
@Composable
private fun GuidePanel(
    state: PlayerUiState,
    focusRequester: FocusRequester,
    onSelectDay: (Long) -> Unit,
    onProgrammeClick: (com.mateof.kanal.data.db.EpgEntity) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(520.dp)
            .fillMaxHeight(0.94f)
            .padding(20.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF20B1120))
            .padding(22.dp)
            .focusRequester(focusRequester)
            .focusGroup()
    ) {
        Text(
            state.playable?.title.orEmpty(),
            style = MaterialTheme.typography.titleLarge,
            color = KanalColors.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "Programación",
            style = MaterialTheme.typography.labelMedium,
            color = KanalColors.OnSurfaceFaint
        )
        Spacer(Modifier.height(14.dp))

        ChannelGuide(
            days = state.guideDays,
            selectedDay = state.selectedDay,
            programmes = state.dayProgrammes,
            modifier = Modifier.weight(1f),
            emptyMessage = "El proveedor no envía guía para este canal.",
            onSelectDay = onSelectDay,
            onProgrammeClick = onProgrammeClick
        )

        Spacer(Modifier.height(14.dp))
        KanalButton("Cerrar", onClose, tone = ButtonTone.Primary)
    }
}

@Composable
private fun Osd(
    state: PlayerUiState,
    controlsActive: Boolean,
    controlsFocus: FocusRequester,
    resizeLabel: String,
    onCycleResize: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenDetail: () -> Unit
) {
    val playable = state.playable ?: return
    val isCompactWidth = isCompact
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xF205070C))
            )
            .padding(start = 56.dp, end = 56.dp, top = 60.dp, bottom = 40.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (playable.logo.isNotBlank()) {
                Box(
                    Modifier
                        .size(84.dp, 52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x33FFFFFF))
                ) {
                    ArtworkImage(
                        url = playable.logo,
                        label = playable.title,
                        fallbackIcon = Icons.Outlined.ClosedCaption,
                        contentScale = ContentScale.Fit,
                        padding = 6.dp
                    )
                }
                Spacer(Modifier.width(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    playable.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        if (playable.subtitle.isNotBlank()) append(playable.subtitle)
                        if (state.channelCount > 0 && state.channelIndex >= 0) {
                            if (isNotEmpty()) append("  ·  ")
                            append("${state.channelIndex + 1} de ${state.channelCount}")
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = KanalColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        val now = state.now
        if (playable.isLive && now != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatClock(now.start)} – ${formatClock(now.stop)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = KanalColors.OnSurfaceMuted
                )
                Spacer(Modifier.width(14.dp))
                // A long programme name is exactly the part worth reading, so it
                // scrolls instead of being cut with an ellipsis.
                Text(
                    now.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = KanalColors.Accent,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .weight(1f)
                        .scrollingTitle()
                )
            }
            Spacer(Modifier.height(8.dp))
            val total = (now.stop - now.start).toFloat()
            val elapsed = (System.currentTimeMillis() - now.start).toFloat()
            ThinProgress(if (total > 0) elapsed / total else 0f, Modifier.fillMaxWidth())

            if (now.description.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    now.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = KanalColors.OnSurfaceMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(if (isCompactWidth) 1f else 0.72f)
                )
            }
            state.next?.let { next ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "DESPUÉS  ",
                        style = MaterialTheme.typography.labelSmall,
                        color = KanalColors.Secondary
                    )
                    Text(
                        "${formatClock(next.start)}  ${next.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = KanalColors.OnSurfaceFaint,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .weight(1f)
                            .scrollingTitle()
                    )
                }
            }
        } else if (state.durationMs > 0) {
            ThinProgress(
                state.positionMs.toFloat() / state.durationMs.toFloat(),
                Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${formatDuration(state.positionMs)} / ${formatDuration(state.durationMs)}" +
                    if (!state.playing) "   ·   en pausa" else "",
                style = MaterialTheme.typography.labelMedium,
                color = KanalColors.OnSurfaceMuted
            )
        }

        Spacer(Modifier.height(18.dp))

        // FlowRow, not Row: on a phone the five controls do not fit across one
        // line and the last of them ended up clipped off the screen.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(controlsFocus)
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!playable.isLive) {
                KanalButton(
                    text = if (state.playing) "Pausa" else "Reproducir",
                    onClick = onTogglePlay,
                    icon = if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    tone = ButtonTone.Primary
                )
                KanalButton("10 s", onSeekBack, icon = Icons.Outlined.Replay10)
                KanalButton("10 s", onSeekForward, icon = Icons.Outlined.Forward10)
            }
            if (playable.isLive && state.now != null) {
                KanalButton(
                    text = "Ficha",
                    onClick = onOpenDetail,
                    icon = Icons.Outlined.Info,
                    tone = ButtonTone.Primary
                )
            }
            if (playable.isLive && state.guideDays.isNotEmpty()) {
                KanalButton(
                    text = "Guía",
                    onClick = onOpenGuide,
                    icon = Icons.Outlined.CalendarMonth
                )
            }
            KanalButton(resizeLabel, onCycleResize, icon = Icons.Outlined.AspectRatio)
            KanalButton("Audio y subtítulos", onOpenTracks, icon = Icons.Outlined.Tune)
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = when {
                controlsActive -> "Izquierda y derecha para moverte por los botones · ATRÁS vuelve al vídeo"
                playable.isLive -> "Arriba y abajo cambian de canal · OK abre los controles y la guía · ATRÁS sale"
                else -> "Izquierda y derecha ±10 s · OK abre los controles · ATRÁS sale"
            },
            style = MaterialTheme.typography.labelSmall,
            color = KanalColors.OnSurfaceFaint
        )
    }
}

@Composable
private fun TrackPanel(
    state: PlayerUiState,
    focusRequester: FocusRequester,
    onSelect: (TrackOption) -> Unit,
    onDisableSubtitles: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(430.dp)
            .fillMaxHeight(0.92f)
            .padding(24.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF20B1120))
            .padding(24.dp)
            .focusRequester(focusRequester)
            .focusGroup()
    ) {
        Text("Audio y subtítulos", style = MaterialTheme.typography.titleLarge, color = KanalColors.OnBackground)
        Spacer(Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("Audio", style = MaterialTheme.typography.labelMedium, color = KanalColors.OnSurfaceFaint)
            }
            if (state.audioTracks.isEmpty()) {
                item {
                    Text(
                        "Sin pistas de audio alternativas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KanalColors.OnSurfaceFaint
                    )
                }
            }
            items(state.audioTracks, key = { "a-${it.id}" }) { option ->
                TrackRow(option) { onSelect(option) }
            }

            item {
                Spacer(Modifier.height(12.dp))
                Text("Subtítulos", style = MaterialTheme.typography.labelMedium, color = KanalColors.OnSurfaceFaint)
            }
            if (state.subtitleTracks.isEmpty()) {
                item {
                    Text(
                        "Esta emisión no trae subtítulos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KanalColors.OnSurfaceFaint
                    )
                }
            }
            items(state.subtitleTracks, key = { "s-${it.id}" }) { option ->
                TrackRow(option) { onSelect(option) }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KanalButton("Sin subtítulos", onDisableSubtitles)
            KanalButton("Cerrar", onClose, tone = ButtonTone.Primary)
        }
    }
}

@Composable
private fun TrackRow(option: TrackOption, onClick: () -> Unit) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (option.selected) KanalColors.SurfaceVariant else Color.Transparent,
        focusedColor = KanalColors.Accent,
        focusedScale = 1.0f
    ) { focused ->
        Text(
            text = (if (option.selected) "● " else "○ ") + option.label,
            style = MaterialTheme.typography.bodySmall,
            color = when {
                focused -> Color(0xFF06231F)
                option.selected -> KanalColors.Accent
                else -> KanalColors.OnSurfaceMuted
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

private fun nextResizeMode(current: Int): Int = when (current) {
    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

private fun resizeLabel(mode: Int): String = when (mode) {
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Estirar"
    else -> "Ajustar"
}
