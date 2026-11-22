package com.mateof.kanal.ui.screens.player

import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.mateof.kanal.R
import com.mateof.kanal.player.MiniPlayer
import com.mateof.kanal.player.PipController
import com.mateof.kanal.ui.isCompact
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    private val mini: MiniPlayer,
    pip: PipController
) : ViewModel() {
    val showing = mini.showing
    val inPip = pip.active

    fun togglePause() = mini.togglePause()
    fun close() = mini.close()
}

/**
 * The picture left floating over the app by swiping the player down.
 *
 * Dragged anywhere and let go, it settles against the nearer side so it does not
 * end up covering the middle of a list. A tap brings it back full size; the
 * cross stops it. When the app itself goes into the system's small window, the
 * picture takes the whole of it, since there is no app to see at that size.
 */
@OptIn(UnstableApi::class)
@Composable
fun MiniPlayerOverlay(
    hidden: Boolean,
    onExpand: (kind: String, itemId: String, startMillis: Long) -> Unit,
    /** Room kept clear at the bottom, for the navigation bar of the narrow layout. */
    bottomInset: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val vm: MiniPlayerViewModel = hiltViewModel()
    val showing by vm.showing.collectAsStateWithLifecycle()
    val inPip by vm.inPip.collectAsStateWithLifecycle()
    val current = showing ?: return
    if (hidden) return

    if (inPip) {
        Box(modifier.fillMaxSize().background(Color.Black)) {
            MiniVideo(current.player, Modifier.fillMaxSize())
        }
        return
    }

    val playing by playingState(current.player)
    val compact = isCompact
    val width = if (compact) 200.dp else 320.dp
    val height = width * 9f / 16f
    val margin = 12.dp
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxX = with(density) { (maxWidth - width - margin).toPx() }
        val maxY = with(density) { (maxHeight - height - margin - bottomInset).toPx() }
        val minEdge = with(density) { margin.toPx() }
        val position = remember { Animatable(Offset(maxX, maxY), Offset.VectorConverter) }
        // A rotation changes the room: keep the window inside it.
        LaunchedEffect(maxX, maxY) {
            position.snapTo(
                Offset(
                    position.value.x.coerceIn(minEdge, maxX.coerceAtLeast(minEdge)),
                    position.value.y.coerceIn(minEdge, maxY.coerceAtLeast(minEdge))
                )
            )
        }

        Box(
            Modifier
                .offset { IntOffset(position.value.x.roundToInt(), position.value.y.roundToInt()) }
                .size(width, height)
                .shadow(12.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp))
                .pointerInput(current) {
                    detectTapGestures(
                        onTap = { onExpand(current.kind, current.itemId, current.startMillis) }
                    )
                }
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragEnd = {
                            val centre = position.value.x + size.width / 2f
                            val snapX = if (centre < constraints.maxWidth / 2f) minEdge else maxX
                            scope.launch {
                                position.animateTo(
                                    Offset(snapX, position.value.y),
                                    spring(stiffness = Spring.StiffnessMediumLow)
                                )
                            }
                        }
                    ) { change, drag ->
                        change.consume()
                        scope.launch {
                            val next = position.value + drag
                            position.snapTo(
                                Offset(
                                    next.x.coerceIn(minEdge, maxX.coerceAtLeast(minEdge)),
                                    next.y.coerceIn(minEdge, maxY.coerceAtLeast(minEdge))
                                )
                            )
                        }
                    }
                }
        ) {
            MiniVideo(current.player, Modifier.fillMaxSize())

            if (current.failed) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(8.dp)
                ) {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Text(
                        stringResource(R.string.mini_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }

            Text(
                current.playable.title,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            MiniButton(
                icon = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                description = stringResource(if (playing) R.string.player_pause else R.string.player_play),
                onClick = vm::togglePause,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
            )
            MiniButton(
                icon = Icons.Outlined.Close,
                description = stringResource(R.string.mini_close),
                onClick = vm::close,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
            )
        }
    }
}

/**
 * Tap targets with no focus of their own: the corner only exists for a finger,
 * and a focusable here would pull the remote's focus out of the screen below.
 */
@Composable
private fun MiniButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(0xB3000000))
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MiniVideo(player: Player, modifier: Modifier) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                this.player = player
            }
        },
        update = { view -> view.player = player },
        // The full player takes the same instance back; this view must let go
        // of its surface rather than keep a claim on it.
        onRelease = { view -> view.player = null },
        modifier = modifier
    )
}

@Composable
private fun playingState(player: Player): androidx.compose.runtime.State<Boolean> {
    val state = remember(player) { mutableStateOf(player.playWhenReady) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                state.value = playWhenReady
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return state
}
