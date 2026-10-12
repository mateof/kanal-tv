package com.mateof.kanal.link

import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.repo.Playable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** What the player here is showing, for the devices that ask. */
data class NowPlaying(val title: String, val positionMs: Long, val isLive: Boolean)

/**
 * Something another device asked this one to play, in the player route's own
 * terms: [kind] is LIVE, MOVIE or SERIES from the catalogue here, or
 * [KIND_REMOTE] with [itemId] the key of a playable that came as a bare url.
 */
data class IncomingPlay(
    val kind: String,
    val itemId: String,
    val start: Long,
    val title: String,
    val from: String
)

const val KIND_REMOTE = "REMOTE"

/** Sources another device wants to add here, waiting for the person in front of it. */
data class PendingOffer(
    val id: Long,
    val from: Sender,
    val host: String,
    val sources: List<Source>,
    internal val answer: CompletableDeferred<Boolean>
)

/**
 * Where the network side hands things to the interface, and the interface
 * answers. The server runs on its own threads and never touches navigation; it
 * leaves a request here and the navigation host picks it up.
 */
@Singleton
class LinkInbox @Inject constructor() {

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    fun reportNowPlaying(value: NowPlaying?) {
        _nowPlaying.value = value
    }

    // --- Play -----------------------------------------------------------------

    private val _play = MutableStateFlow<IncomingPlay?>(null)

    /** Held until the interface is past the gate, like the cast panel request. */
    val play: StateFlow<IncomingPlay?> = _play.asStateFlow()

    fun requestPlay(value: IncomingPlay) {
        _play.value = value
    }

    fun consumePlay() {
        _play.value = null
    }

    /**
     * Playables that arrived as bare urls, by the key their route carries. A
     * few are kept rather than one: the player looks its key up again if it
     * is recreated, and that must not find the next one sent.
     */
    private val remote = LinkedHashMap<String, Playable>()
    private val counter = AtomicLong(System.currentTimeMillis())

    @Synchronized
    fun putRemote(playable: Playable): String {
        val key = counter.incrementAndGet().toString(36)
        remote[key] = playable
        while (remote.size > 5) remote.remove(remote.keys.first())
        return key
    }

    @Synchronized
    fun remote(key: String): Playable? = remote[key]

    /** Where a film sent from another device should start, consumed once. */
    private val startPositions = mutableMapOf<String, Long>()

    @Synchronized
    fun setStartPosition(kind: String, itemId: String, positionMs: Long) {
        startPositions["$kind/$itemId"] = positionMs
    }

    @Synchronized
    fun takeStartPosition(kind: String, itemId: String): Long? = startPositions.remove("$kind/$itemId")

    // --- Stop -----------------------------------------------------------------

    private val _stops = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The sender took the stream back: whatever plays here has to let go of it. */
    val stops: SharedFlow<Unit> = _stops.asSharedFlow()

    @Volatile
    private var stoppedAt = 0L

    fun requestStop() {
        stoppedAt = System.currentTimeMillis()
        _stops.tryEmit(Unit)
    }

    /**
     * For a player being torn down: the screen may close it before its own
     * collector of [stops] runs, and then it would park the stream in the
     * preview as if the person had simply gone back to the list.
     */
    fun stoppedRecently(): Boolean = System.currentTimeMillis() - stoppedAt < 5_000

    // --- Sources --------------------------------------------------------------

    private val _offer = MutableStateFlow<PendingOffer?>(null)
    val offer: StateFlow<PendingOffer?> = _offer.asStateFlow()
    private val offerIds = AtomicLong()

    /**
     * Shows the offer and waits for an answer. Never accepted on its own: a
     * source carries an account's password, and anyone on the network can
     * send one. A second offer while one is on screen is turned down rather
     * than queued behind it.
     */
    suspend fun ask(from: Sender, host: String, sources: List<Source>): Boolean {
        val pending = PendingOffer(offerIds.incrementAndGet(), from, host, sources, CompletableDeferred())
        synchronized(this) {
            if (_offer.value != null) return false
            _offer.value = pending
        }
        return try {
            withTimeoutOrNull(OFFER_TIMEOUT_MS) { pending.answer.await() } ?: false
        } finally {
            synchronized(this) { if (_offer.value?.id == pending.id) _offer.value = null }
        }
    }

    private val _imported = MutableStateFlow(false)

    /** Sources were just added from another device; the setup screen can step aside. */
    val imported: StateFlow<Boolean> = _imported.asStateFlow()

    fun markImported() {
        _imported.value = true
    }

    fun consumeImported() {
        _imported.value = false
    }

    fun answer(offer: PendingOffer, accept: Boolean) {
        offer.answer.complete(accept)
        synchronized(this) { if (_offer.value?.id == offer.id) _offer.value = null }
    }

    companion object {
        /** Long enough to find the remote; the sender waits exactly this. */
        const val OFFER_TIMEOUT_MS = 90_000L
    }
}
