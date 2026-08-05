package com.mateof.kanal.player

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.mateof.kanal.core.log.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Passes a live player from one screen to the other instead of tearing it down.
 *
 * The channel list and the full-screen player each used to build their own
 * player, so leaving full screen with BACK dropped the connection and the
 * preview opened a second one to the same channel: a visible gap, another
 * connection against the provider's limit, and the buffer thrown away. Parking
 * the instance here lets the other screen adopt it and carry on mid-stream.
 *
 * A parked player keeps running, so it cannot be left behind: whoever parks it
 * sets a deadline, and if nobody adopts it by then it is released.
 */
@UnstableApi
@Singleton
class PlayerHandover @Inject constructor(
    private val logger: FileLogger
) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    private var parked: ExoPlayer? = null
    private var parkedKey: String? = null
    private var parkedSignature: String? = null
    private var expiry: Job? = null

    /**
     * Leaves [player] for the other screen to pick up.
     *
     * @param key identifies the stream, so an adopter only takes it when it
     *   wants exactly what is already playing.
     * @param signature the player build settings, so a player made under
     *   settings that have since changed is not handed on.
     * @param listener the parking screen's listener, removed on the way in;
     *   the adopter attaches its own.
     */
    fun park(player: ExoPlayer, key: String, signature: String, listener: Player.Listener?) {
        discard()
        listener?.let(player::removeListener)
        parked = player
        parkedKey = key
        parkedSignature = signature
        logger.d("Handover", "Reproductor aparcado para '$key'")

        expiry = scope.launch {
            delay(PARK_TIMEOUT_MS)
            if (parked != null) {
                logger.d("Handover", "Nadie recogió el reproductor, se libera")
                discard()
            }
        }
    }

    /** @return the parked player when it is already playing [key], else null. */
    fun adopt(key: String, signature: String): ExoPlayer? {
        val player = parked ?: return null
        if (parkedKey != key || parkedSignature != signature) return null
        expiry?.cancel()
        parked = null
        parkedKey = null
        parkedSignature = null
        logger.d("Handover", "Reproductor recogido para '$key', sigue la conexión")
        return player
    }

    /** Releases anything parked. Called when the handover can no longer happen. */
    fun discard() {
        expiry?.cancel()
        expiry = null
        parked?.release()
        parked = null
        parkedKey = null
        parkedSignature = null
    }

    private companion object {
        /** Long enough to cross a screen transition, short enough not to leak. */
        const val PARK_TIMEOUT_MS = 8_000L
    }
}
