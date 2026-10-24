package com.mateof.kanal.cast

import androidx.annotation.StringRes
import com.mateof.kanal.R
import com.mateof.kanal.cast.relay.CastRelay
import com.mateof.kanal.cast.relay.RelayEnded
import com.mateof.kanal.cast.relay.RelayNotice
import com.mateof.kanal.cast.relay.RelayStatus
import com.mateof.kanal.core.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

enum class CastPhase { ACTIVE, RECONNECTING, ENDED }

/** How to word a UPnP transport state, or null for one without a name of its own. */
@StringRes
fun tvStateRes(state: String): Int? = when (state) {
    "PLAYING" -> R.string.cast_tv_state_playing
    "TRANSITIONING" -> R.string.cast_tv_state_transitioning
    "PAUSED_PLAYBACK" -> R.string.cast_tv_state_paused
    "STOPPED" -> R.string.cast_tv_state_stopped
    "NO_MEDIA_PRESENT" -> R.string.cast_tv_state_no_media
    else -> null
}

data class CastIncident(
    val at: Long,
    val text: UiText,
    /** The literal detail — what the server or the television said — for the second line. */
    val detail: String? = null,
    val level: Level = Level.INFO
) {
    enum class Level { INFO, WARN, ERROR }
}

/** The phone's side of the relay: where the television fetches from. */
data class CastLinkInfo(
    val interfaceName: String?,
    val subnet: String,
    val address: String,
    val port: Int?
)

/** Everything known about the send in progress, or the last one if it stopped by itself. */
data class CastSession(
    val device: CastDevice,
    val request: CastRequest,
    val viaPhone: Boolean,
    /** Sent directly first, and through the phone after the television could not fetch it. */
    val fallback: Boolean,
    val link: CastLinkInfo?,
    /** The relay session currently serving it; see [CastRelay.generation]. */
    val generation: Int,
    val startedAt: Long,
    /** The last time it was (re)sent. */
    val connectedAt: Long,
    /** When it stopped for good, which is where the time on the panel stops too. */
    val endedAt: Long? = null,
    val phase: CastPhase = CastPhase.ACTIVE,
    /** Which automatic attempt is running while [phase] is RECONNECTING; 0 for one the user asked for. */
    val attempt: Int = 0,
    val tvState: String? = null,
    val position: CastPosition? = null,
    val servedBytes: Long = 0,
    val rateBytes: Long = 0,
    val averageBytes: Long = 0,
    /** Bytes per second, a sample a second, oldest first. */
    val history: List<Long> = emptyList(),
    val tvConnected: Boolean = false,
    /** Times the television asked for the stream again by itself. */
    val tvComebacks: Int = 0,
    /** Times it was sent again: by the user or automatically. */
    val resends: Int = 0,
    val tvAgent: String? = null,
    val upstreamMs: Long? = null,
    /** Newest first. */
    val incidents: List<CastIncident> = emptyList(),
    val endProblem: String? = null,
    val endHint: UiText? = null
)

/**
 * Follows a send for the transmission panel: how fast the television is being
 * fed, what it says it is doing, and everything that went wrong on the way.
 *
 * Only watches. Deciding — sending again, giving up — stays in
 * [CastController], which tells this what happened.
 */
@Singleton
class CastMonitor @Inject constructor(
    private val relay: CastRelay,
    private val renderers: Renderers
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _session = MutableStateFlow<CastSession?>(null)
    val session: StateFlow<CastSession?> = _session.asStateFlow()

    private var sampler: Job? = null
    private val throughput = Throughput()

    /** Bytes served by relays that have since been replaced, so the total survives a resend. */
    private var carriedBytes = 0L
    private var lastRelayBytes = 0L
    private var activeMs = 0L
    private var lastTvState: String? = null

    /** How many screens are showing the panel; the television is only asked while someone looks. */
    private val viewers = AtomicInteger(0)

    /** When the panel last moved or paused the television; see [controlled]. */
    @Volatile
    private var controlledAt = 0L

    init {
        scope.launch {
            relay.notices.collect { notice ->
                val current = _session.value ?: return@collect
                if (notice.generation != current.generation) return@collect
                when (notice.kind) {
                    RelayNotice.Kind.TV_RECONNECTED -> {
                        // A seek through the relay is a new request from the
                        // television, and not the comeback the count is about.
                        if (System.currentTimeMillis() - controlledAt < CONTROL_ECHO_MS) return@collect
                        _session.update { it?.copy(tvComebacks = it.tvComebacks + 1) }
                        note(UiText(R.string.cast_event_tv_came_back))
                    }
                    RelayNotice.Kind.UPSTREAM_CUT ->
                        note(UiText(R.string.cast_event_upstream_cut), notice.detail, CastIncident.Level.WARN)
                    RelayNotice.Kind.UPSTREAM_REFUSED ->
                        note(UiText(R.string.cast_event_upstream_refused, notice.detail.orEmpty()), level = CastIncident.Level.WARN)
                    RelayNotice.Kind.REFUSED ->
                        note(UiText(R.string.cast_event_refused, notice.detail.orEmpty()), level = CastIncident.Level.WARN)
                }
            }
        }
    }

    /**
     * A send went out. [resumed] keeps what was gathered so far — the same
     * thing to the same television, sent again.
     */
    @Synchronized
    fun started(device: CastDevice, request: CastRequest, outcome: CastOutcome, resumed: Boolean) {
        val now = System.currentTimeMillis()
        val previous = _session.value?.takeIf { resumed }
        val running = relay.status.value as? RelayStatus.Running
        val link = running?.takeIf { outcome.viaPhone }?.link?.let {
            CastLinkInfo(
                interfaceName = it.interfaceName,
                subnet = "${it.subnet.address.hostAddress}/${it.subnet.prefixLength}",
                address = it.address.hostAddress.orEmpty(),
                port = relay.port()
            )
        }
        if (previous == null) {
            carriedBytes = 0
            activeMs = 0
            throughput.reset()
            lastTvState = null
        } else {
            carriedBytes += lastRelayBytes
            throughput.rebase()
        }
        lastRelayBytes = 0
        val base = previous ?: CastSession(
            device = device,
            request = request,
            viaPhone = outcome.viaPhone,
            fallback = outcome.route == CastSource.ROUTE_FALLBACK,
            link = link,
            generation = relay.generation,
            startedAt = now,
            connectedAt = now
        )
        _session.value = base.copy(
            viaPhone = outcome.viaPhone,
            fallback = outcome.route == CastSource.ROUTE_FALLBACK,
            link = link,
            generation = relay.generation,
            connectedAt = now,
            phase = CastPhase.ACTIVE,
            attempt = 0,
            resends = if (previous != null) previous.resends + 1 else 0,
            endProblem = null,
            endHint = null,
            endedAt = null
        )
        note(if (previous == null) UiText(R.string.cast_event_started, device.name) else UiText(R.string.cast_event_reconnected))
        outcome.playWarning?.let { note(UiText(R.string.cast_event_play_error), it, CastIncident.Level.WARN) }
        ensureSampling()
    }

    fun reconnecting(attempt: Int, reason: String?) {
        _session.update { it?.copy(phase = CastPhase.RECONNECTING, attempt = attempt, tvConnected = false, rateBytes = 0) }
        note(
            if (attempt > 0) UiText(R.string.cast_event_reconnecting_auto, attempt, CastController.MAX_AUTO_ATTEMPTS)
            else UiText(R.string.cast_event_reconnecting),
            reason,
            CastIncident.Level.WARN
        )
    }

    /** The relay stopped and nothing more will be tried. */
    fun ended(ended: RelayEnded) = stopped(ended.problem, ended.hint)

    /** A resend that did not work, or the last of the automatic ones. */
    fun failed(problem: String?, hint: UiText?) = stopped(problem, hint)

    private fun stopped(problem: String?, hint: UiText?) {
        _session.update {
            it?.copy(
                phase = CastPhase.ENDED,
                attempt = 0,
                tvConnected = false,
                rateBytes = 0,
                endProblem = problem,
                endHint = hint,
                endedAt = System.currentTimeMillis()
            )
        }
        note(UiText(R.string.cast_event_stopped), problem, CastIncident.Level.ERROR)
    }

    /** Stopped on purpose, or replaced by another send: nothing left to show. */
    fun clear() {
        _session.value = null
        sampler?.cancel()
        sampler = null
    }

    fun note(text: UiText, detail: String? = null, level: CastIncident.Level = CastIncident.Level.INFO) {
        val incident = CastIncident(System.currentTimeMillis(), text, detail, level)
        _session.update { it?.copy(incidents = (listOf(incident) + it.incidents).take(MAX_INCIDENTS)) }
    }

    /** What the television answered when asked, by the relay's watchdog or by the panel. */
    fun tvState(state: String?) {
        _session.update { it?.copy(tvState = state) }
        if (state != null && state != lastTvState) {
            if (lastTvState != null) note(UiText(R.string.cast_event_tv_state, state))
            lastTvState = state
        }
    }

    /**
     * The panel told the television to pause, carry on or move. What it says
     * it is doing is taken now rather than at the next poll, so the buttons do
     * not flip back for a second.
     */
    fun controlled(state: String? = null, positionMs: Long? = null) {
        controlledAt = System.currentTimeMillis()
        if (state != null) tvState(state)
        if (positionMs != null) {
            _session.update { current ->
                current?.copy(position = CastPosition(msToUpnpTime(positionMs), current.position?.duration))
            }
        }
    }

    fun tvSilent(misses: Int, of: Int, detail: String?) {
        note(UiText(R.string.cast_event_tv_silent, misses, of), detail, CastIncident.Level.WARN)
    }

    /** The panel is showing; the television is asked more often while it is. */
    fun watching(on: Boolean) {
        if (on) viewers.incrementAndGet() else viewers.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (on) scope.launch { pollTv() }
    }

    private fun ensureSampling() {
        if (sampler?.isActive == true) return
        sampler = scope.launch {
            var lastPoll = 0L
            while (isActive) {
                delay(SAMPLE_MS)
                val current = _session.value ?: return@launch
                if (current.phase != CastPhase.ACTIVE) continue
                sample(current)
                val now = System.currentTimeMillis()
                // A film's progress bar wants the position every second; a
                // channel has none to show.
                val every = if (current.request.live) POLL_MS else POSITION_POLL_MS
                if (viewers.get() > 0 && now - lastPoll >= every) {
                    lastPoll = now
                    pollTv()
                }
            }
        }
    }

    @Synchronized
    private fun sample(current: CastSession) {
        if (!current.viaPhone) return
        val stats = relay.stats() ?: return
        if (!relay.running(current.generation)) return
        lastRelayBytes = stats.servedBytes
        val total = carriedBytes + stats.servedBytes
        val now = System.currentTimeMillis()
        throughput.add(total, now)
        if (stats.connected) activeMs += SAMPLE_MS
        _session.update {
            it?.copy(
                servedBytes = total,
                rateBytes = throughput.current(),
                averageBytes = if (activeMs > 0) total * 1_000 / activeMs else 0,
                history = throughput.history,
                tvConnected = stats.connected,
                tvAgent = stats.agent ?: it.tvAgent,
                upstreamMs = stats.upstreamMs ?: it.upstreamMs
            )
        }
    }

    private suspend fun pollTv() {
        val device = _session.value?.takeIf { it.phase == CastPhase.ACTIVE }?.device ?: return
        renderers.transportState(device).onSuccess { tvState(it) }
        renderers.position(device).onSuccess { position ->
            _session.update { it?.takeIf { s -> s.device == device }?.copy(position = position) ?: it }
        }
    }

    private companion object {
        const val SAMPLE_MS = 1_000L
        const val POLL_MS = 5_000L
        const val POSITION_POLL_MS = 1_000L
        const val CONTROL_ECHO_MS = 10_000L
        const val MAX_INCIDENTS = 40
    }
}
