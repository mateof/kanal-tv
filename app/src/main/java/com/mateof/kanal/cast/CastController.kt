package com.mateof.kanal.cast

import android.content.Context
import com.mateof.kanal.R
import com.mateof.kanal.cast.relay.CastRelay
import com.mateof.kanal.cast.relay.CastRelayService
import com.mateof.kanal.cast.relay.NetworkInspector
import com.mateof.kanal.cast.relay.RelayEnded
import com.mateof.kanal.core.UiText
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.data.net.redactUrl
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.prefs.CastRouteMode
import com.mateof.kanal.data.prefs.StreamFormat
import com.mateof.kanal.data.repo.Playable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/** What is being sent, with both of the addresses it may go out as. */
data class CastRequest(
    val title: String,
    /** What the direct route hands over: the url currently playing, as before. */
    val directUrl: String,
    /**
     * What the relay fetches. The MPEG-TS variant where there is one: webOS's
     * DLNA player copes badly with HLS, and a single url is all the relay can
     * pass on anyway.
     */
    val relayUrl: String,
    val userAgent: String,
    val live: Boolean,
    /** Where it sits in the catalogue, for the transmission panel. */
    val origin: CastOrigin? = null
) {
    companion object {
        fun from(playable: Playable, directUrl: String = playable.url): CastRequest {
            val urls = listOf(playable.url) + playable.fallbackUrls
            val ts = playable.candidateIds.indexOf(StreamFormat.TS.extension)
            val preferred = urls.getOrNull(ts)?.takeIf { ts >= 0 } ?: directUrl
            // A downloaded film plays from a content:// uri here, which the relay
            // cannot fetch; the provider's copy stays among the fallbacks.
            val relayUrl = sequenceOf(preferred, directUrl).plus(urls)
                .firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
                ?: directUrl
            return CastRequest(
                playable.title,
                directUrl,
                relayUrl,
                playable.userAgent,
                playable.isLive,
                CastOrigin(playable.kind, playable.sourceId, playable.itemId, playable.epgChannelId, playable.subtitle)
            )
        }
    }
}

data class CastOrigin(
    val kind: ContentKind,
    val sourceId: String,
    val itemId: String,
    val epgChannelId: String,
    val subtitle: String
)

/** How a send went out, for the sheet to say so. */
data class CastOutcome(
    val route: String,
    /** What the television answered to Play when it was wrong about it; see [CastController.relayed]. */
    val playWarning: String? = null
) {
    val viaPhone: Boolean get() = route != CastSource.ROUTE_DIRECT
}

/** A send that went out again, for the screens that had given it up. */
data class CastResumed(val device: CastDevice, val outcome: CastOutcome)

/** The relay stopped while the television was still being told about it. */
class RelayStopped(message: String, val hint: UiText?) : IOException(message)

/**
 * Sends to a television by whichever route can work: directly, or through the
 * relay on this phone when the television cannot reach the provider itself.
 *
 * The one place both the lists and the player go through, so the decision, the
 * fallback and what is remembered about each television agree.
 */
@Singleton
class CastController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val upnp: UpnpClient,
    private val relay: CastRelay,
    private val network: NetworkInspector,
    private val prefs: AppPreferences,
    private val monitor: CastMonitor,
    private val logger: FileLogger
) {
    private val memory = RouteMemory()

    /** Outlives a send: a television may answer Play long after it started playing. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One send at a time: a resend must not cross one the user just asked for. */
    private val sending = Mutex()
    private var autoJob: Job? = null

    @Volatile
    private var autoAttempts = 0

    private val _ended = MutableSharedFlow<RelayEnded>(extraBufferCapacity = 4)

    /**
     * Sends that stopped for good; the screens clear their "playing on". Not
     * one being sent again by itself: as far as the screens go, that one is
     * still playing.
     */
    val ended: SharedFlow<RelayEnded> = _ended.asSharedFlow()

    private val _resumed = MutableSharedFlow<CastResumed>(extraBufferCapacity = 4)

    /** A send that had stopped and went out again from the transmission panel. */
    val resumed: SharedFlow<CastResumed> = _resumed.asSharedFlow()

    private val _reclaiming = MutableSharedFlow<CastDevice>(extraBufferCapacity = 4)

    /**
     * About to send again something a screen took back when it stopped. That
     * screen has to let go of the stream first: most accounts allow a single
     * connection, and the television would be refused.
     */
    val reclaiming: SharedFlow<CastDevice> = _reclaiming.asSharedFlow()

    val session: StateFlow<CastSession?> = monitor.session

    init {
        scope.launch {
            relay.ended.collect { ended ->
                // After any send in flight: it may be the one that just ended,
                // and then the send says so itself.
                sending.withLock { }
                onRelayEnded(ended)
            }
        }
    }

    suspend fun send(device: CastDevice, request: CastRequest): Result<CastOutcome> {
        cancelAutomatic()
        return deliver(device, request, resumed = false)
    }

    /** Sends what is (or was last) on the television again, to the same television. */
    suspend fun reconnect(): Result<CastOutcome> {
        val session = monitor.session.value
            ?: return Result.failure(IllegalStateException("no hay ningún envío que reconectar"))
        cancelAutomatic()
        logger.i("Cast", "Reconectar '${session.request.title}' en ${session.device.name}, a petición del usuario")
        monitor.reconnecting(0, null)
        if (session.phase == CastPhase.ENDED) {
            _reclaiming.tryEmit(session.device)
            delay(SLOT_RELEASE_MS)
        }
        return deliver(session.device, session.request, resumed = true)
            .onSuccess { _resumed.tryEmit(CastResumed(session.device, it)) }
            .onFailure { failure ->
                val hint = CastHints.of(failure)
                monitor.failed(failure.message, hint)
                // The relay was closed to send again: whoever still shows it
                // playing has to hear that it no longer is.
                _ended.tryEmit(RelayEnded(session.device, failure.message, hint, session.generation))
            }
    }

    suspend fun stop(device: CastDevice): Result<Unit> = withContext(Dispatchers.IO) {
        cancelAutomatic()
        monitor.clear()
        val result = upnp.stop(device)
        relay.close("envío detenido")
        result
    }

    /**
     * Stops whatever is on the television from the transmission panel, and
     * tells the screens that still think it is playing.
     */
    suspend fun stopCurrent() {
        val session = monitor.session.value ?: return
        stop(session.device)
        _ended.tryEmit(RelayEnded(session.device, null, null, session.generation, byUser = true))
    }

    /**
     * [reconnect] and [stopCurrent] for a screen that may be gone before the
     * television answers: a webOS set takes thirty seconds over Play.
     */
    fun reconnectInBackground() {
        scope.launch { reconnect() }
    }

    fun stopInBackground() {
        scope.launch { stopCurrent() }
    }

    /** Forgets a send that stopped by itself, once the user has seen it. */
    fun dismiss() {
        if (monitor.session.value?.phase == CastPhase.ENDED) monitor.clear()
    }

    private suspend fun deliver(device: CastDevice, request: CastRequest, resumed: Boolean): Result<CastOutcome> =
        withContext(Dispatchers.IO) {
            sending.withLock {
                if (!resumed) monitor.clear()
                runCatching {
                    // Whatever was relayed before stops: the television is about
                    // to be given something else either way.
                    relay.close(if (resumed) "se reenvía a ${device.name}" else "nuevo envío a ${device.name}")
                    val settings = prefs.settings.first()
                    when (settings.castRouteMode) {
                        CastRouteMode.DIRECT -> direct(device, request)
                        CastRouteMode.RELAY -> relayed(device, request, CastSource.ROUTE_RELAY, settings.castRelayPort)
                        CastRouteMode.AUTO -> automatic(device, request, settings.castRelayPort)
                    }
                }
                    .onSuccess {
                        CastRelayService.clearEndNotice(context)
                        monitor.started(device, request, it, resumed)
                    }
                    .onFailure { logger.w("Cast", "No se pudo enviar '${request.title}' a ${device.name}: ${it.message}") }
            }
        }

    private fun onRelayEnded(ended: RelayEnded) {
        if (ended.byUser) {
            cancelAutomatic()
            monitor.clear()
            _ended.tryEmit(ended)
            return
        }
        val session = monitor.session.value
        // A relay nobody follows any more: a resend that failed already said so.
        if (session == null || session.generation != ended.generation || session.phase != CastPhase.ACTIVE) return
        if (System.currentTimeMillis() - session.connectedAt > STABLE_MS) autoAttempts = 0
        if (ended.recoverable && autoAttempts < MAX_AUTO_ATTEMPTS) {
            autoJob = scope.launch { reconnectByItself(session, ended) }
            return
        }
        monitor.ended(ended)
        _ended.tryEmit(ended)
    }

    /**
     * Sends again after the server side failed: the phone lost it for a
     * moment, or it cut the stream and the television gave up. A VPN that is
     * reconnecting takes a few seconds, hence the wait before each try.
     */
    private suspend fun reconnectByItself(session: CastSession, ended: RelayEnded) {
        var reason = ended.problem
        var hint = ended.hint
        while (autoAttempts < MAX_AUTO_ATTEMPTS) {
            autoAttempts++
            logger.i("Cast", "Reconexión automática $autoAttempts/$MAX_AUTO_ATTEMPTS con ${session.device.name}: $reason")
            monitor.reconnecting(autoAttempts, reason)
            delay(AUTO_RETRY_DELAY_MS)
            val failure = deliver(session.device, session.request, resumed = true).exceptionOrNull() ?: return
            reason = failure.message
            hint = CastHints.of(failure)
        }
        logger.w("Cast", "Se deja de reconectar con ${session.device.name} tras $MAX_AUTO_ATTEMPTS intentos")
        monitor.failed(reason, hint)
        val final = RelayEnded(session.device, reason, hint, session.generation)
        CastRelayService.postEndNotice(context, final)
        _ended.tryEmit(final)
    }

    private fun cancelAutomatic() {
        autoJob?.cancel()
        autoJob = null
        autoAttempts = 0
    }

    private suspend fun automatic(device: CastDevice, request: CastRequest, port: Int): CastOutcome {
        val hostName = hostOf(request.directUrl)
        val host = hostName?.let { resolve(it) }
        val key = RouteMemory.Key(device.udn, hostName.orEmpty())
        val remembered = memory[key]

        val wanted = when {
            remembered != null -> {
                logger.i("Cast", "Automático: ${device.name} + $hostName ya funcionó en ${remembered.name.lowercase()}")
                remembered
            }
            host == null -> {
                logger.i("Cast", "Automático: no se pudo resolver $hostName, se va directo")
                CastRoute.DIRECT
            }
            else -> {
                val tv = resolve(device.host)
                val decision = CastRouting.decide(
                    host = host,
                    tvSubnet = tv?.let { network.lanLinkFor(it)?.subnet },
                    hostViaVpn = CastRouting.viaVpn(network.routes(), host)
                )
                logger.i("Cast", "Automático: ${decision.route.name.lowercase()} porque ${decision.reason}")
                decision.route
            }
        }

        if (wanted == CastRoute.RELAY) {
            try {
                return relayed(device, request, CastSource.ROUTE_RELAY, port).also { memory[key] = CastRoute.RELAY }
            } catch (unavailable: RelayUnavailable) {
                // The guess said relay but the phone cannot offer one from here;
                // the direct route might still work, and costs nothing to try.
                logger.w("Cast", "Automático: el relay no está disponible (${unavailable.message}); se prueba directo")
            }
        }

        return try {
            direct(device, request).also { memory[key] = CastRoute.DIRECT }
        } catch (failure: Exception) {
            if (!CastRouting.shouldFallBack(failure)) throw failure
            logger.i("Cast", "Directo falló (${failure.message}); se reintenta a través del móvil")
            try {
                relayed(device, request, CastSource.ROUTE_FALLBACK, port).also { memory[key] = CastRoute.RELAY }
            } catch (unavailable: RelayUnavailable) {
                throw CastFailure(failure, unavailable)
            }
        }
    }

    private suspend fun direct(device: CastDevice, request: CastRequest): CastOutcome {
        upnp.play(device, CastSource.direct(request.directUrl), request.title).getOrThrow()
        return CastOutcome(CastSource.ROUTE_DIRECT)
    }

    private suspend fun relayed(device: CastDevice, request: CastRequest, route: String, port: Int): CastOutcome {
        val tv = resolve(device.host) ?: throw RelayUnavailable(
            "no se sabe la dirección de ${device.name}",
            UiText(R.string.cast_hint_no_lan, CastRouting.suggestedTunnel(null))
        )
        val mime = if (request.live) {
            // Live is MPEG-TS in practice — Dispatcharr's own .m3u8 serves TS
            // too — and announcing it is what webOS wants. A real manifest is
            // caught by the relay when it arrives.
            Dlna.MIME_TS
        } else {
            Dlna.mimeOf(request.relayUrl).takeIf { it != Dlna.MIME_HLS } ?: Dlna.MIME_TS
        }
        val features = if (request.live) Dlna.LIVE_FEATURES else Dlna.SEEKABLE_FEATURES
        val url = relay.open(
            device = device,
            tv = tv,
            upstreamHost = hostOf(request.relayUrl)?.let { resolve(it) },
            upstreamUrl = request.relayUrl,
            userAgent = request.userAgent,
            mime = mime,
            features = features,
            live = request.live,
            title = request.title,
            port = port
        )
        val generation = relay.generation
        val source = CastSource(url, mime, features, route)
        val play = scope.async { upnp.play(device, source, request.title) }
        // webOS holds Play open until its player settles, and on a live stream
        // answers 501 after thirty seconds while it is already showing it. What
        // the television does with the relay says more than what it answers.
        while (!play.isCompleted && !relay.tvReading(PLAYBACK_EVIDENCE_BYTES) && relay.running(generation)) {
            delay(EVIDENCE_POLL_MS)
        }
        if (!relay.running(generation)) {
            // The relay gave up by itself — the phone could not reach the
            // server, typically — and knows why better than Play would.
            val ended = relay.lastEnded?.takeIf { it.generation == generation }
            throw RelayStopped(ended?.problem ?: "el relay se detuvo", ended?.hint)
        }
        var warning: String? = null
        if (play.isCompleted) {
            play.await().onFailure { error ->
                if (!keepsReading()) {
                    relay.close("${device.name} rechazó la dirección del relay")
                    throw error
                }
                logger.w("Cast", "${device.name} contestó a Play con un error (${error.message}), pero está recibiendo la emisión; se sigue")
                warning = error.message
            }
        } else {
            logger.i("Cast", "${device.name} ya está recibiendo la emisión; no se espera a que conteste a Play")
            scope.launch { lateAnswer(device, play, generation) }
        }
        logger.i("Cast", "'${request.title}' sale de ${redactUrl(request.relayUrl)} a través del móvil")
        return CastOutcome(route, warning)
    }

    /**
     * Play answering after the send was already given up as working. A failure
     * only counts if the television has also stopped reading; otherwise it is
     * the renderer's wording, and the relay's watchdog has the last word.
     */
    private suspend fun lateAnswer(device: CastDevice, play: Deferred<Result<Unit>>, generation: Int) {
        play.await()
            .onSuccess { logger.d("Cast", "${device.name} contestó a Play") }
            .onFailure { error ->
                if (relay.generation != generation) return
                if (keepsReading()) {
                    logger.w("Cast", "${device.name} contestó a Play con un error (${error.message}), pero sigue recibiendo la emisión; se sigue")
                    monitor.note(UiText(R.string.cast_event_play_error), error.message, CastIncident.Level.WARN)
                } else {
                    relay.endIfStill(generation, "${device.name} no consiguió reproducir: ${error.message}", CastHints.of(error))
                }
            }
    }

    /**
     * Whether the television goes on reading after an error from Play. Asked
     * twice, a moment apart: one that gives up sends the error first and hangs
     * up right after, and the first look would still see it connected.
     */
    private suspend fun keepsReading(): Boolean {
        if (!relay.tvReading()) return false
        delay(READING_GRACE_MS)
        return relay.tvReading()
    }

    private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun resolve(host: String): InetAddress? =
        runCatching { InetAddress.getByName(host) }
            .onFailure { logger.w("Cast", "No se pudo resolver $host: ${it.message}") }
            .getOrNull()

    companion object {
        const val MAX_AUTO_ATTEMPTS = 3

        /** A couple of seconds of HD: more than a renderer reads just to sniff the format. */
        private const val PLAYBACK_EVIDENCE_BYTES = 2L * 1024 * 1024
        private const val EVIDENCE_POLL_MS = 250L
        private const val READING_GRACE_MS = 3_000L
        private const val AUTO_RETRY_DELAY_MS = 5_000L

        /** Providers do not always free the slot the instant the socket goes. */
        private const val SLOT_RELEASE_MS = 1_500L

        /** Running this long since the last (re)send earns the automatic attempts back. */
        private const val STABLE_MS = 2 * 60_000L
    }
}
