package com.mateof.kanal.cast.relay

import android.content.Context
import com.mateof.kanal.R
import com.mateof.kanal.cast.CastDevice
import com.mateof.kanal.cast.CastRouting
import com.mateof.kanal.cast.RelayUnavailable
import com.mateof.kanal.core.UiText
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.net.HttpProvider
import com.mateof.kanal.data.net.redactUrl
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RelayStatus {
    data object Idle : RelayStatus

    data class Running(
        val device: CastDevice,
        val title: String,
        val link: LanLink
    ) : RelayStatus
}

/**
 * A relay that stopped on its own rather than because a new send replaced it.
 * [problem] is null for an ordinary end — the Stop button, a television that
 * stopped asking — and set when the user should hear why.
 */
data class RelayEnded(val device: CastDevice, val problem: String?, val hint: UiText?)

/**
 * Owns the relay server and the one session it serves.
 *
 * The server is started here rather than inside [CastRelayService] because the
 * relay's address has to be known before the television is told about it, and
 * a service starts asynchronously. The service is what keeps the process in the
 * foreground while it runs, and stopping it stops the server.
 */
@Singleton
class CastRelay @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: HttpProvider,
    private val network: NetworkInspector,
    private val logger: FileLogger
) {
    private val _status = MutableStateFlow<RelayStatus>(RelayStatus.Idle)
    val status: StateFlow<RelayStatus> = _status.asStateFlow()

    private val _ended = MutableSharedFlow<RelayEnded>(extraBufferCapacity = 4)
    val ended: SharedFlow<RelayEnded> = _ended.asSharedFlow()

    /** The last end, for the service to word its parting notification. */
    @Volatile
    var lastEnded: RelayEnded? = null
        private set

    private var server: RelayServer? = null

    /**
     * The shared client — same pool, same interceptors, so every request still
     * reaches the log — with a shorter connect timeout. A television gives up
     * on a silent address after about fifteen seconds; the provider being out
     * of reach has to be known, and answered, before that.
     */
    private val upstream by lazy {
        http.client.newBuilder().connectTimeout(UPSTREAM_CONNECT_S, TimeUnit.SECONDS).build()
    }

    /**
     * Counts sessions, so the service that served an old one cannot close the
     * new one on its way out.
     */
    @Volatile
    var generation = 0
        private set

    /**
     * Starts serving [upstreamUrl] to [tv] alone and returns the address to
     * give it.
     *
     * @param upstreamHost the provider's address, only to word the advice.
     * @throws RelayUnavailable when the phone cannot be reached from the
     *   television's network.
     */
    @Synchronized
    fun open(
        device: CastDevice,
        tv: InetAddress,
        upstreamHost: InetAddress?,
        upstreamUrl: String,
        userAgent: String,
        mime: String,
        features: String,
        live: Boolean,
        title: String,
        port: Int
    ): String {
        // Stopped without passing through Idle: the service following the
        // status would otherwise take that as its cue to leave.
        server?.stop()
        server = null
        val tunnel = CastRouting.suggestedTunnel(upstreamHost)
        val link: LanLink
        val relay: RelayServer
        try {
            link = network.lanLinkFor(tv) ?: throw RelayUnavailable(
                "el móvil no tiene dirección en la red de la tele (${tv.hostAddress})",
                UiText(R.string.cast_hint_no_lan, tunnel)
            )
            if (CastRouting.viaVpn(network.routes(), tv)) {
                // The relay would listen on the right interface, but the answers
                // to the television would go into the tunnel and never reach it.
                throw RelayUnavailable(
                    "la VPN también se lleva el tráfico hacia la tele (${link.subnet})",
                    UiText(R.string.cast_hint_vpn_covers_tv, tunnel)
                )
            }
            relay = RelayServer(link.address, port, upstream, events(device, tunnel))
            try {
                relay.start()
            } catch (e: IOException) {
                throw RelayUnavailable(
                    "no se pudo abrir el puerto $port en ${link.address.hostAddress}: ${e.message}",
                    UiText(R.string.cast_hint_port_busy, port)
                )
            }
        } catch (e: RelayUnavailable) {
            shutdown()
            throw e
        }
        val session = RelaySession(
            token = RelayTokens.issue(),
            upstreamUrl = upstreamUrl,
            userAgent = userAgent,
            mime = mime,
            features = features,
            live = live,
            client = tv
        )
        relay.session = session
        server = relay
        val url = relay.url(session)
        logger.i(
            "Cast",
            "Relay en ${redactUrl(url)} (${link.interfaceName ?: "?"}, ${link.subnet}), " +
                "sólo para ${tv.hostAddress}; origen ${redactUrl(upstreamUrl)} · $mime"
        )
        generation++
        lastEnded = null
        _status.value = RelayStatus.Running(device, title, link)
        CastRelayService.start(context)
        return url
    }

    /** Milliseconds since the television last had a connection open, or null when not running. */
    fun idleMs(): Long? = server?.idleMs()

    /** Closes session [generation] only if no newer one has replaced it. */
    @Synchronized
    fun closeIfStill(generation: Int, reason: String) {
        if (generation == this.generation) close(reason)
    }

    /** Stopped by the app: a new send, or the user bringing the stream back. */
    @Synchronized
    fun close(reason: String) {
        if (server == null) return
        logger.i("Cast", "Relay cerrado: $reason")
        lastEnded = null
        shutdown()
    }

    /** Stopped by itself; the screens and the notification are told. */
    @Synchronized
    fun end(problem: String?, hint: UiText? = null) {
        val running = _status.value as? RelayStatus.Running ?: return
        if (problem != null) logger.w("Cast", "Relay detenido: $problem") else logger.i("Cast", "Relay terminado")
        val ended = RelayEnded(running.device, problem, hint)
        lastEnded = ended
        shutdown()
        _ended.tryEmit(ended)
    }

    private fun shutdown() {
        server?.stop()
        server = null
        _status.value = RelayStatus.Idle
    }

    private fun events(device: CastDevice, tunnel: String) = object : RelayServer.Events {
        override fun info(message: String) = logger.i("Cast", message)

        override fun warn(message: String) = logger.w("Cast", message)

        override fun upstreamUnreachable(error: IOException) {
            end(
                "el móvil no llega al servidor (${error.javaClass.simpleName}: ${error.message})",
                UiText(R.string.cast_hint_no_route, tunnel)
            )
        }

        override fun unsupported(contentType: String) {
            end(
                "el servidor devolvió una lista HLS ($contentType), que no se puede pasar por el móvil",
                UiText(R.string.cast_hint_relay_hls)
            )
        }
    }

    private companion object {
        const val UPSTREAM_CONNECT_S = 8L
    }
}
