package com.mateof.kanal.cast

import com.mateof.kanal.R
import com.mateof.kanal.cast.relay.CastRelay
import com.mateof.kanal.cast.relay.NetworkInspector
import com.mateof.kanal.cast.relay.RelayEnded
import com.mateof.kanal.core.UiText
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.net.redactUrl
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.prefs.CastRouteMode
import com.mateof.kanal.data.prefs.StreamFormat
import com.mateof.kanal.data.repo.Playable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
    val live: Boolean
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
            return CastRequest(playable.title, directUrl, relayUrl, playable.userAgent, playable.isLive)
        }
    }
}

/** How a send went out, for the sheet to say so. */
data class CastOutcome(val route: String) {
    val viaPhone: Boolean get() = route != CastSource.ROUTE_DIRECT
}

/**
 * Sends to a television by whichever route can work: directly, or through the
 * relay on this phone when the television cannot reach the provider itself.
 *
 * The one place both the lists and the player go through, so the decision, the
 * fallback and what is remembered about each television agree.
 */
@Singleton
class CastController @Inject constructor(
    private val upnp: UpnpClient,
    private val relay: CastRelay,
    private val network: NetworkInspector,
    private val prefs: AppPreferences,
    private val logger: FileLogger
) {
    private val memory = RouteMemory()

    /** Relays that ended on their own; the screens clear their "playing on". */
    val ended: SharedFlow<RelayEnded> = relay.ended

    suspend fun send(device: CastDevice, request: CastRequest): Result<CastOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Whatever was relayed before stops: the television is about to
                // be given something else either way.
                relay.close("nuevo envío a ${device.name}")
                val settings = prefs.settings.first()
                when (settings.castRouteMode) {
                    CastRouteMode.DIRECT -> direct(device, request)
                    CastRouteMode.RELAY -> relayed(device, request, CastSource.ROUTE_RELAY, settings.castRelayPort)
                    CastRouteMode.AUTO -> automatic(device, request, settings.castRelayPort)
                }
            }.onFailure { logger.w("Cast", "No se pudo enviar '${request.title}' a ${device.name}: ${it.message}") }
        }

    suspend fun stop(device: CastDevice): Result<Unit> = withContext(Dispatchers.IO) {
        val result = upnp.stop(device)
        relay.close("envío detenido")
        result
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
        upnp.play(device, CastSource(url, mime, features, route), request.title)
            .onFailure { relay.close("${device.name} rechazó la dirección del relay") }
            .getOrThrow()
        logger.i("Cast", "'${request.title}' sale de ${redactUrl(request.relayUrl)} a través del móvil")
        return CastOutcome(route)
    }

    private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun resolve(host: String): InetAddress? =
        runCatching { InetAddress.getByName(host) }
            .onFailure { logger.w("Cast", "No se pudo resolver $host: ${it.message}") }
            .getOrNull()
}
