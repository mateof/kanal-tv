package com.mateof.kanal.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.repo.PlaybackRepository
import com.mateof.kanal.data.repo.Playable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The picture kept running in a corner of the app while the rest of it is used.
 *
 * The system's picture-in-picture shrinks the whole activity, so with it there
 * is no app left to browse. This keeps the same player instance — connection
 * and buffer included — floating over the navigation instead, and hands it back
 * to the full player when the corner is tapped.
 *
 * Nothing but the player itself lives here: the full player's reconnection,
 * guide and stream probing stay with the full player. In the corner a failure
 * gets a couple of plain retries and, after that, a tap opens the full player,
 * which knows how to explain it.
 */
@UnstableApi
@Singleton
class MiniPlayer @Inject constructor(
    private val playback: PlaybackRepository,
    private val pip: PipController,
    private val logger: FileLogger
) {
    /** What is in the corner, and the route that brings it back full size. */
    data class Showing(
        val player: ExoPlayer,
        val playable: Playable,
        val kind: String,
        val itemId: String,
        val startMillis: Long,
        val failed: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _showing = MutableStateFlow<Showing?>(null)
    val showing: StateFlow<Showing?> = _showing.asStateFlow()

    private var key = ""
    private var signature = ""
    private var retries = 0
    private var retryJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            pip.setVideoSize(videoSize.width, videoSize.height)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                retries = 0
                _showing.value = _showing.value?.copy(failed = false)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val current = _showing.value ?: return
            logger.w("Mini", "Fallo en la ventana: ${error.errorCodeName}")
            if (retries >= MAX_RETRIES) {
                _showing.value = current.copy(failed = true)
                return
            }
            retries++
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(RETRY_DELAY_MS)
                val exo = _showing.value?.player ?: return@launch
                val position = exo.currentPosition
                exo.prepare()
                if (!current.playable.isLive && position > 0) exo.seekTo(position)
                exo.playWhenReady = true
            }
        }
    }

    /** Takes [player] over from the full player, which must already have let go of it. */
    fun show(
        player: ExoPlayer,
        playable: Playable,
        kind: String,
        itemId: String,
        startMillis: Long,
        streamKey: String,
        signature: String
    ) {
        close()
        key = streamKey
        this.signature = signature
        retries = 0
        player.addListener(listener)
        player.videoSize.takeIf { it.width > 0 }?.let { pip.setVideoSize(it.width, it.height) }
        player.playWhenReady = true
        _showing.value = Showing(player, playable, kind, itemId, startMillis)
        logger.d("Mini", "'${playable.title}' pasa a la ventana")
    }

    /** @return the player when it is playing [streamKey] under the same build settings. */
    fun adopt(streamKey: String, signature: String): ExoPlayer? {
        val current = _showing.value ?: return null
        if (key != streamKey || this.signature != signature) return null
        retryJob?.cancel()
        current.player.removeListener(listener)
        _showing.value = null
        return current.player
    }

    fun togglePause() {
        val exo = _showing.value?.player ?: return
        exo.playWhenReady = !exo.playWhenReady
    }

    /** Closes the corner and gives the provider its connection back. */
    fun close() {
        val current = _showing.value ?: return
        retryJob?.cancel()
        _showing.value = null
        val exo = current.player
        exo.removeListener(listener)
        if (current.playable.sourceId.isNotBlank()) {
            val position = exo.currentPosition
            val duration = exo.duration.takeIf { it > 0 } ?: 0L
            scope.launch { playback.record(current.playable, position, duration) }
        }
        exo.release()
        pip.clearVideo()
        logger.d("Mini", "Ventana cerrada")
    }

    private companion object {
        const val MAX_RETRIES = 2
        const val RETRY_DELAY_MS = 2_000L
    }
}
