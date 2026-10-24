package com.mateof.kanal.cast

import com.mateof.kanal.cast.gcast.GoogleCastClient
import com.mateof.kanal.cast.webos.WebOsClient
import com.mateof.kanal.data.prefs.AppPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** How Kanal talks to a television. Each can be switched off in the settings; listed in this order. */
enum class CastProtocol { GOOGLE_CAST, WEBOS, DLNA }

/**
 * What every protocol has to offer the controller, the monitor and the
 * notification. The transport state is reported in UPnP's words (PLAYING,
 * PAUSED_PLAYBACK, TRANSITIONING, STOPPED…) whatever the protocol says, since
 * that is what everything downstream already understands.
 */
interface Renderer {
    suspend fun play(device: CastDevice, source: CastSource, title: String, live: Boolean): Result<Unit>
    suspend fun stop(device: CastDevice): Result<Unit>
    suspend fun pause(device: CastDevice): Result<Unit>
    suspend fun resume(device: CastDevice): Result<Unit>
    suspend fun seek(device: CastDevice, positionMs: Long): Result<Unit>
    suspend fun transportState(device: CastDevice): Result<String?>
    suspend fun position(device: CastDevice): Result<CastPosition>
}

/**
 * A television that would not fetch what it was given. The direct route
 * falls back to the relay on it, like a UPnP 716.
 */
class RendererRefused(message: String, val fetchFailed: Boolean) : IOException(message)

/** Hands each call to the client of the device's protocol. */
@Singleton
class Renderers @Inject constructor(
    private val upnp: UpnpClient,
    private val webOs: WebOsClient,
    private val googleCast: GoogleCastClient
) : Renderer {

    private fun of(device: CastDevice): Renderer = when (device.protocol) {
        CastProtocol.DLNA -> upnp
        CastProtocol.WEBOS -> webOs
        CastProtocol.GOOGLE_CAST -> googleCast
    }

    override suspend fun play(device: CastDevice, source: CastSource, title: String, live: Boolean) =
        of(device).play(device, source, title, live)

    override suspend fun stop(device: CastDevice) = of(device).stop(device)
    override suspend fun pause(device: CastDevice) = of(device).pause(device)
    override suspend fun resume(device: CastDevice) = of(device).resume(device)
    override suspend fun seek(device: CastDevice, positionMs: Long) = of(device).seek(device, positionMs)
    override suspend fun transportState(device: CastDevice) = of(device).transportState(device)
    override suspend fun position(device: CastDevice) = of(device).position(device)
}

/** Looks for televisions with every protocol switched on in the settings. */
@Singleton
class CastDiscovery @Inject constructor(
    private val prefs: AppPreferences,
    private val upnp: UpnpClient,
    private val webOs: WebOsClient,
    private val googleCast: GoogleCastClient
) {
    suspend fun discover(): List<CastDevice> = coroutineScope {
        val settings = prefs.settings.first()
        val dlna = async { if (settings.castDlna) upnp.discover() else emptyList() }
        val lg = async { if (settings.castWebOs) webOs.discover() else emptyList() }
        val google = async { if (settings.castGoogle) googleCast.discover() else emptyList() }
        // Grouped by protocol, in the order the list shows them.
        google.await() + lg.await() + dlna.await()
    }

    /** An address typed in by hand: whichever switched-on protocol answers there. */
    suspend fun describeManual(address: String): List<CastDevice> = coroutineScope {
        val settings = prefs.settings.first()
        val dlna = async { if (settings.castDlna) listOfNotNull(upnp.describeManual(address)) else emptyList() }
        val lg = async { if (settings.castWebOs) listOfNotNull(webOs.describeManual(address)) else emptyList() }
        val google = async { if (settings.castGoogle) listOfNotNull(googleCast.describeManual(address)) else emptyList() }
        google.await() + lg.await() + dlna.await()
    }

    suspend fun kanalEnabled(): Boolean = prefs.settings.first().castKanal
}
