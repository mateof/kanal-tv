package com.mateof.kanal.ui.screens.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
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
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PictureInPictureAlt
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import androidx.compose.ui.res.stringResource
import com.mateof.kanal.R
import com.mateof.kanal.cast.CastDevice
import com.mateof.kanal.core.resolve
import com.mateof.kanal.core.formatClock
import com.mateof.kanal.core.formatDuration
import com.mateof.kanal.ui.components.ArtworkImage
import com.mateof.kanal.ui.components.ChannelGuide
import com.mateof.kanal.ui.components.ButtonTone
import com.mateof.kanal.ui.components.FocusableSurface
import com.mateof.kanal.ui.components.KanalButton
import com.mateof.kanal.ui.components.KanalTextField
import com.mateof.kanal.ui.components.ProgrammeDetail
import com.mateof.kanal.ui.components.ProgrammeDialog
import com.mateof.kanal.ui.components.SeekBar
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
private enum class Mode { Watching, Controls, Tracks, Guide, Cast }

/** One button of the control bar, so the first can be given the focus anchor. */
private data class ControlAction(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tone: ButtonTone,
    val onClick: () -> Unit
)

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
    val inPip by vm.inPip.collectAsStateWithLifecycle()

    var mode by remember { mutableStateOf(Mode.Watching) }
    var osdVisible by remember { mutableStateOf(true) }
    var osdTick by remember { mutableIntStateOf(0) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    // Set when OK opens the control bar, cleared when its key-up is swallowed.
    var swallowCentreUp by remember { mutableStateOf(false) }

    val stageFocus = remember { FocusRequester() }
    val controlsFocus = remember { FocusRequester() }
    val tracksFocus = remember { FocusRequester() }
    val guideFocus = remember { FocusRequester() }
    val castFocus = remember { FocusRequester() }
    var detail by remember { mutableStateOf<ProgrammeDetail?>(null) }

    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val swipeThreshold = with(LocalDensity.current) { 110.dp.toPx() }

    fun goFullscreenLandscape() {
        val window = activity?.window ?: return
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Whatever the gestures did to the screen belongs to the player, so it is
    // undone on the way out rather than left for the next screen to inherit.
    DisposableEffect(activity) {
        onDispose {
            val window = activity?.window ?: return@onDispose
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowInsetsControllerCompat(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

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

    // A window that small has room for the picture and nothing else, so any
    // panel still open is put away as it shrinks.
    LaunchedEffect(inPip) {
        if (inPip) {
            mode = Mode.Watching
            osdVisible = false
            detail = null
        }
    }

    // Focus follows the mode, so the arrows always land where the user expects.
    // The control bar and the track panel appear with the same frame that
    // changes the mode, so their target may not be attached yet: keep trying
    // for a moment instead of silently leaving focus on the video.
    // Also keyed on the sheet: when it closes, focus has to come back to the bar
    // or the arrows have nothing to move from and the remote goes dead.
    LaunchedEffect(mode, detail == null) {
        val target = when (mode) {
            Mode.Watching -> stageFocus
            Mode.Controls -> controlsFocus
            Mode.Tracks -> tracksFocus
            Mode.Guide -> guideFocus
            Mode.Cast -> castFocus
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

    fun goBack() {
        when (mode) {
            Mode.Tracks, Mode.Guide, Mode.Cast -> mode = Mode.Controls
            Mode.Controls -> mode = Mode.Watching
            // The title sitting over the picture has to go the moment BACK is
            // pressed, not on the next auto-hide tick.
            Mode.Watching -> if (osdVisible) osdVisible = false else onBack(liveChannelId())
        }
    }

    // Kept for gesture navigation, which never produces a key event. The remote's
    // BACK key is handled in the key preview below, because with the player in
    // the tree the event never reached this dispatcher.
    BackHandler(enabled = detail == null) { goBack() }

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
                    // Anything showing goes away, including the title band:
                    // switching mode alone left it up until its timer ran out,
                    // which read as the tap not having worked.
                    if (mode != Mode.Watching || osdVisible) {
                        mode = Mode.Watching
                        osdVisible = false
                    } else {
                        openControls()
                    }
                }
            }
            // Gestures for the hand, mirroring what the remote does with keys.
            .pointerInput(swipeThreshold) {
                var travelled = Offset.Zero
                detectDragGestures(
                    onDragStart = { travelled = Offset.Zero },
                    onDragEnd = {
                        val (dx, dy) = travelled
                        when {
                            abs(dx) > abs(dy) && abs(dx) > swipeThreshold ->
                                onBack(liveChannelId())

                            dy < -swipeThreshold -> goFullscreenLandscape()
                            dy > swipeThreshold -> vm.enterPip()
                        }
                    }
                ) { change, drag ->
                    travelled += drag
                    change.consume()
                }
            }
            // Preview, not onKeyEvent: a key event reaches the focused child
            // first and only then bubbles to its ancestors. Compose fires a click
            // on KEY_UP, so the up of the very OK that opened the bar was landing
            // on the button that had just taken focus — one press opened the bar
            // and activated its first button. Only the root sees the event early
            // enough to swallow it.
            .onPreviewKeyEvent { event ->
                // BACK is handled here rather than through BackHandler: with the
                // player view in the tree the key never reached the activity's
                // dispatcher, so the panels could not be closed with the remote.
                // The sheet keeps its own handler, so let it through for that.
                if (event.key == Key.Back && detail == null) {
                    if (event.type == KeyEventType.KeyUp) goBack()
                    return@onPreviewKeyEvent true
                }
                val centre = event.key == Key.DirectionCenter || event.key == Key.Enter
                if (swallowCentreUp && centre && event.type == KeyEventType.KeyUp) {
                    swallowCentreUp = false
                    true
                } else {
                    false
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
                        swallowCentreUp = true
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

        if (!inPip && (state.loading || (state.buffering && state.error == null))) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    color = KanalColors.Accent,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(56.dp)
                )
                // Say what is happening: a silent reconnection looks identical to
                // a stall, and the user deserves to know the app is on it.
                if (state.reconnectAttempt > 0) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.player_reconnecting, state.reconnectAttempt),
                        style = MaterialTheme.typography.labelLarge,
                        color = KanalColors.OnSurfaceMuted
                    )
                }
            }
        }

        state.error?.takeIf { !inPip }?.let { errorText ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(40.dp)
                    .focusGroup(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    errorText.resolve(),
                    style = MaterialTheme.typography.titleLarge,
                    color = KanalColors.Error
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KanalButton(stringResource(R.string.common_retry), vm::retry, tone = ButtonTone.Primary)
                    KanalButton(stringResource(R.string.common_back), { onBack(liveChannelId()) })
                }
            }
        }

        AnimatedVisibility(
            visible = osdVisible && state.error == null && !inPip,
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
                onSeekTo = { target -> vm.seekTo(target); poke() },
                onOpenTracks = { mode = Mode.Tracks; poke() },
                onOpenCast = { mode = Mode.Cast; vm.searchCastDevices(); poke() },
                onEnterPip = { vm.enterPip() },
                pipAvailable = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O,
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

        if (mode == Mode.Cast && !inPip) {
            CastSheet(
                state = state,
                focusRequester = castFocus,
                onSearch = vm::searchCastDevices,
                onPick = { device -> vm.castTo(device); mode = Mode.Watching },
                onBringBack = { vm.stopCast() },
                onAdd = { vm.addCastDevice(it) },
                onClose = { mode = Mode.Controls }
            )
        }

        if (mode == Mode.Tracks && !inPip) {
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

        if (mode == Mode.Guide && !inPip) {
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
            stringResource(R.string.live_schedule),
            style = MaterialTheme.typography.labelMedium,
            color = KanalColors.OnSurfaceFaint
        )
        Spacer(Modifier.height(14.dp))

        ChannelGuide(
            days = state.guideDays,
            selectedDay = state.selectedDay,
            programmes = state.dayProgrammes,
            modifier = Modifier.weight(1f),
            emptyMessage = stringResource(R.string.player_no_guide),
            onSelectDay = onSelectDay,
            onProgrammeClick = onProgrammeClick
        )

        Spacer(Modifier.height(14.dp))
        KanalButton(stringResource(R.string.common_close), onClose, tone = ButtonTone.Primary)
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
    onSeekTo: (Long) -> Unit,
    onOpenCast: () -> Unit,
    onEnterPip: () -> Unit,
    pipAvailable: Boolean,
    onOpenTracks: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenDetail: () -> Unit
) {
    val playable = state.playable ?: return
    val isCompactWidth = isCompact

    // Which button the focus is on, so its name can be shown while the rest stay
    // as bare icons.
    var focusedLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(controlsActive) { if (!controlsActive) focusedLabel = null }
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
                        stringResource(R.string.player_next_label) + "  ",
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
            var scrubbing by remember { mutableStateOf<Long?>(null) }
            SeekBar(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                onSeek = { target -> onSeekTo(target) },
                onScrub = { scrubbing = it },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            val shown = scrubbing ?: state.positionMs
            Text(
                buildString {
                    append(formatDuration(shown))
                    append(" / ")
                    append(formatDuration(state.durationMs))
                    scrubbing?.let { target ->
                        val delta = target - state.positionMs
                        val sign = if (delta >= 0) "+" else "−"
                        append("   ·   $sign${formatDuration(kotlin.math.abs(delta))}")
                    } ?: run {
                        // Not while buffering: after a seek the player is stopped
                        // but on its way, and calling that "paused" reads as if
                        // the jump had failed.
                        if (!state.playing && !state.buffering) {
                            append("   ·   ").append(stringResource(R.string.player_paused))
                        }
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (scrubbing != null) KanalColors.Accent else KanalColors.OnSurfaceMuted
            )
        }

        Spacer(Modifier.height(18.dp))

        // Focus goes to a real button, never to a container: requesting focus on
        // a focus group can silently land nowhere, and then the arrows have no
        // anchor to search from and the remote appears dead.
        val actions = buildList {
            if (!playable.isLive) {
                add(
                    ControlAction(
                        label = if (state.playing) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                        icon = if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        tone = ButtonTone.Primary,
                        onClick = onTogglePlay
                    )
                )
                add(ControlAction("10 s", Icons.Outlined.Replay10, ButtonTone.Neutral, onSeekBack))
                add(ControlAction("10 s", Icons.Outlined.Forward10, ButtonTone.Neutral, onSeekForward))
            }
            if (playable.isLive && state.now != null) {
                add(ControlAction(stringResource(R.string.player_details), Icons.Outlined.Info, ButtonTone.Primary, onOpenDetail))
            }
            if (playable.isLive && state.guideDays.isNotEmpty()) {
                add(ControlAction(stringResource(R.string.player_guide), Icons.Outlined.CalendarMonth, ButtonTone.Neutral, onOpenGuide))
            }
            add(ControlAction(resizeLabel, Icons.Outlined.AspectRatio, ButtonTone.Neutral, onCycleResize))
            add(ControlAction(stringResource(R.string.player_audio_subtitles), Icons.Outlined.Tune, ButtonTone.Neutral, onOpenTracks))
            add(ControlAction(stringResource(R.string.cast_send), Icons.Outlined.Cast, ButtonTone.Neutral, onOpenCast))
            if (pipAvailable) {
                add(
                    ControlAction(
                        stringResource(R.string.player_pip),
                        Icons.Outlined.PictureInPictureAlt,
                        ButtonTone.Neutral,
                        onEnterPip
                    )
                )
            }
        }

        // FlowRow, not Row: on a phone the five controls do not fit across one
        // line and the last of them ended up clipped off the screen.
        FlowRow(
            modifier = Modifier.fillMaxWidth().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            actions.forEachIndexed { index, action ->
                KanalButton(
                    text = action.label,
                    onClick = action.onClick,
                    icon = action.icon,
                    tone = action.tone,
                    iconOnly = true,
                    onFocusState = { focused -> if (focused) focusedLabel = action.label },
                    modifier = if (index == 0) Modifier.focusRequester(controlsFocus) else Modifier
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        // Seven labelled buttons ate half the screen over the picture, so the
        // labels went. The one that matters is the focused button's, and it
        // takes the line the hint was already using rather than a new one.
        Text(
            text = when {
                focusedLabel != null -> focusedLabel.orEmpty()
                controlsActive -> stringResource(R.string.player_hint_controls)
                playable.isLive -> stringResource(R.string.player_hint_live)
                else -> stringResource(R.string.player_hint_vod)
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
        Text(stringResource(R.string.player_audio_subtitles), style = MaterialTheme.typography.titleLarge, color = KanalColors.OnBackground)
        Spacer(Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(stringResource(R.string.player_audio), style = MaterialTheme.typography.labelMedium, color = KanalColors.OnSurfaceFaint)
            }
            if (state.audioTracks.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.player_no_audio_tracks),
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
                Text(stringResource(R.string.player_subtitles), style = MaterialTheme.typography.labelMedium, color = KanalColors.OnSurfaceFaint)
            }
            if (state.subtitleTracks.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.player_no_subtitle_tracks),
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
            KanalButton(stringResource(R.string.player_no_subtitles), onDisableSubtitles)
            KanalButton(stringResource(R.string.common_close), onClose, tone = ButtonTone.Primary)
        }
    }
}

/**
 * The devices found on the network, and where the stream currently is.
 *
 * Drawn as an overlay inside the screen like the other sheets, so it obeys the
 * same focus rules as the rest of the player.
 */
@Composable
private fun CastSheet(
    state: PlayerUiState,
    focusRequester: FocusRequester,
    onSearch: () -> Unit,
    onPick: (CastDevice) -> Unit,
    onBringBack: () -> Unit,
    onAdd: (String) -> Unit,
    onClose: () -> Unit
) {
    var address by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF20B1120))
            .padding(24.dp)
            .focusGroup()
    ) {
        Text(
            stringResource(R.string.cast_title),
            style = MaterialTheme.typography.titleLarge,
            color = KanalColors.OnBackground
        )
        Spacer(Modifier.height(16.dp))

        state.castingTo?.let { name ->
            Text(
                stringResource(R.string.cast_playing_on, name),
                style = MaterialTheme.typography.titleSmall,
                color = KanalColors.Accent
            )
            Spacer(Modifier.height(12.dp))
        }
        state.castError?.let { reason ->
            Text(
                stringResource(R.string.cast_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = KanalColors.Error
            )
            state.castHint?.let { hint ->
                Spacer(Modifier.height(6.dp))
                Text(
                    hint.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KanalColors.OnSurfaceMuted
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                reason,
                style = MaterialTheme.typography.labelSmall,
                color = KanalColors.OnSurfaceFaint
            )
            Spacer(Modifier.height(12.dp))
        }

        Box(Modifier.weight(1f)) {
            when {
                state.castSearching -> Text(
                    stringResource(R.string.cast_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = KanalColors.OnSurfaceMuted
                )

                state.castDevices.isEmpty() -> Column {
                    Text(
                        stringResource(R.string.cast_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = KanalColors.OnSurfaceMuted
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.cast_none_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = KanalColors.OnSurfaceFaint
                    )
                }

                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.castDevices, key = { it.id }) { device ->
                        FocusableSurface(
                            onClick = { onPick(device) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = KanalColors.Surface,
                            focusedColor = KanalColors.Accent
                        ) { focused ->
                            Text(
                                device.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (focused) Color(0xFF06231F) else KanalColors.OnBackground,
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            KanalTextField(
                value = address,
                onValueChange = { address = it },
                label = stringResource(R.string.cast_address),
                supportingText = stringResource(R.string.cast_address_hint),
                modifier = Modifier.width(if (isCompact) 260.dp else 420.dp)
            )
            KanalButton(
                stringResource(R.string.cast_add),
                { onAdd(address) },
                enabled = address.isNotBlank() && !state.castSearching
            )
        }

        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.castingTo != null) {
                KanalButton(stringResource(R.string.cast_bring_back), onBringBack)
            }
            // The anchor is a button, never the address field: landing on a
            // text field throws the on-screen keyboard up over the sheet before
            // the user has even seen the list.
            KanalButton(
                stringResource(R.string.cast_search_again),
                onSearch,
                modifier = Modifier.focusRequester(focusRequester),
                enabled = !state.castSearching
            )
            KanalButton(stringResource(R.string.common_close), onClose, tone = ButtonTone.Primary)
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
            text = (if (option.selected) "● " else "○ ") +
                (option.name ?: stringResource(R.string.player_track_number, option.number)) +
                option.details,
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

@Composable
private fun resizeLabel(mode: Int): String = when (mode) {
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> stringResource(R.string.player_zoom)
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> stringResource(R.string.player_stretch)
    else -> stringResource(R.string.player_adjust)
}

/**
 * The composition's context is a wrapper around the activity, not the activity
 * itself, so orientation and system bars have to be reached through the chain.
 */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
