package com.mateof.kanal.cast

import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.net.HttpProvider
import com.mateof.kanal.data.net.redactUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/** A media renderer on the local network — usually a television. */
data class CastDevice(
    val name: String,
    /** Absolute URL of the AVTransport service's control endpoint. */
    val controlUrl: String,
    val id: String,
    /**
     * The device's own identity, which survives a new IP address or a different
     * description path. What the relay decision is remembered against.
     */
    val udn: String = id
) {
    /** Where the renderer lives, which is also where its requests to the relay come from. */
    val host: String get() = runCatching { URI(controlUrl).host }.getOrNull().orEmpty()
}

/**
 * Sends a stream to a DLNA/UPnP renderer on the same network.
 *
 * DLNA rather than Chromecast on purpose: it needs no Google Play services, no
 * receiver application to register, and it is what the televisions, consoles and
 * receivers already sitting on a home network speak. Fire TV sticks and other
 * devices without Google services can use it too.
 *
 * The renderer fetches the stream itself, so the provider sees a second client:
 * its user-agent restrictions and connection limits apply.
 */
@Singleton
class UpnpClient @Inject constructor(
    private val http: HttpProvider,
    private val logger: FileLogger
) {
    /**
     * Asks the network who can play video, and returns whoever answers.
     *
     * Discovery is by SSDP: a datagram to the multicast group, and every
     * renderer replies directly to us. Some answer more than once, hence the
     * de-duplication by location.
     */
    suspend fun discover(timeoutMs: Int = 3_000): List<CastDevice> = withContext(Dispatchers.IO) {
        val locations = linkedSetOf<String>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 600
                socket.broadcast = true
                val group = InetAddress.getByName(SSDP_HOST)
                // Asked for in several ways, and each one twice. Not every
                // renderer answers the MediaRenderer device type — some only
                // reply to the AVTransport service, others just to ssdp:all —
                // and SSDP runs over UDP, where a lost datagram is routine.
                repeat(2) {
                    for (target in SEARCH_TARGETS) {
                        val payload = searchFor(target).toByteArray()
                        socket.send(DatagramPacket(payload, payload.size, group, SSDP_PORT))
                    }
                }

                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val reply = String(packet.data, 0, packet.length)
                    headerOf(reply, "LOCATION")?.let(locations::add)
                }
            }
        }.onFailure { logger.w("Cast", "Fallo buscando aparatos", it) }

        logger.i("Cast", "SSDP: ${locations.size} descripciones distintas")
        // ssdp:all answers with everything on the network, printers included.
        // The description is what decides: only those offering AVTransport stay.
        locations.mapNotNull { describe(it) }.distinctBy { it.controlUrl }
    }

    /**
     * Reads a device description and keeps it only if it can play video.
     *
     * Also the way a device typed in by hand is added, for televisions that do
     * not answer discovery.
     */
    suspend fun describe(location: String): CastDevice? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(location).build()
            val xml = http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.d("Cast", "$location respondió ${response.code}")
                    return@withContext null
                }
                response.body?.string().orEmpty()
            }
            if (!xml.contains("AVTransport")) {
                logger.d("Cast", "$location no ofrece AVTransport (${xml.length} bytes)")
                return@withContext null
            }

            val control = tagAfter(xml, "AVTransport", "controlURL") ?: run {
                logger.d("Cast", "$location sin controlURL de AVTransport")
                return@withContext null
            }
            CastDevice(
                name = tag(xml, "friendlyName") ?: URI(location).host,
                controlUrl = URI(location).resolve(control).toString(),
                id = location,
                udn = tag(xml, "UDN") ?: location
            ).also { logger.i("Cast", "Aparato: ${it.name} -> ${it.controlUrl}") }
        }.onFailure { logger.w("Cast", "No se pudo leer la descripción de $location", it) }
            .getOrNull()
    }

    /**
     * Adds a device by address, for televisions that do not answer discovery.
     *
     * Accepts the full description URL, or just a host, in which case the usual
     * paths are tried in turn.
     */
    suspend fun describeManual(address: String): CastDevice? {
        val trimmed = address.trim().removeSuffix("/")
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http", ignoreCase = true) && trimmed.endsWith(".xml")) {
            return describe(trimmed)
        }
        val base = if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "http://$trimmed"
        for (path in COMMON_PATHS) {
            describe("$base$path")?.let { return it }
        }
        return null
    }

    /** Points the renderer at [source] and starts it. */
    suspend fun play(device: CastDevice, source: CastSource, title: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Several televisions refuse a new URI while the transport still
                // holds the previous one, so it is cleared first. A failure here
                // is expected when nothing was playing and must not stop us.
                runCatching { soap(device, "Stop", "<InstanceID>0</InstanceID>") }

                fun setUri(metadata: String) = soap(device, "SetAVTransportURI", buildString {
                    append("<InstanceID>0</InstanceID>")
                    append("<CurrentURI>").append(escape(source.url)).append("</CurrentURI>")
                    append("<CurrentURIMetaData>").append(metadata).append("</CurrentURIMetaData>")
                })

                // Metadata is where renderers are fussiest — a protocolInfo they
                // dislike is enough for a refusal — so a rejection is retried
                // with none at all, which many of them accept.
                runCatching { setUri(escape(metadata(source, title))) }
                    .onFailure { first ->
                        logger.w("Cast", "${device.name} rechazó los metadatos, se reintenta sin ellos: ${first.message}")
                        setUri("")
                    }

                soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
                // The URL goes in the log too: if the renderer accepts the order
                // and still shows nothing, the next thing to check is whether it
                // can fetch that address at all, and that needs the address.
                logger.i("Cast", "Enviado '$title' a ${device.name} (${source.route}): ${redactUrl(source.url)}")
            }
        }

    suspend fun stop(device: CastDevice): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { soap(device, "Stop", "<InstanceID>0</InstanceID>"); Unit }
    }

    /**
     * What the renderer says it is doing — PLAYING, STOPPED, TRANSITIONING… —
     * or a failure when it no longer answers at all. The relay uses it to tell
     * a television that went away from one that is simply buffering.
     */
    suspend fun transportState(device: CastDevice): Result<String?> = withContext(Dispatchers.IO) {
        runCatching {
            tag(soap(device, "GetTransportInfo", "<InstanceID>0</InstanceID>"), "CurrentTransportState")
        }
    }

    private fun soap(device: CastDevice, action: String, body: String): String {
        val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:$action xmlns:u="$AV_TRANSPORT">$body</u:$action></s:Body></s:Envelope>"""

        val request = Request.Builder()
            .url(device.controlUrl)
            .addHeader("SOAPAction", "\"$AV_TRANSPORT#$action\"")
            .addHeader("Connection", "close")
            .post(envelope.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()

        http.client.newCall(request).execute().use { response ->
            val text = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            if (!response.isSuccessful) {
                // A UPnP refusal carries its reason in the body. Reporting only
                // the HTTP status leaves nothing to act on: 500 alone does not
                // distinguish an unsupported format from a busy transport.
                throw UpnpException(
                    action = action,
                    httpCode = response.code,
                    upnpCode = tag(text, "errorCode")?.toIntOrNull(),
                    description = tag(text, "errorDescription")
                )
            }
            return text
        }
    }

    /** Minimal DIDL-Lite; renderers that ignore metadata still play the URL. */
    private fun metadata(source: CastSource, title: String): String =
        """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<item id="0" parentID="-1" restricted="1">""" +
            """<dc:title>${escape(title)}</dc:title>""" +
            """<upnp:class>object.item.videoItem</upnp:class>""" +
            """<res protocolInfo="${Dlna.protocolInfo(source.mime, source.features)}">${escape(source.url)}</res>""" +
            """</item></DIDL-Lite>"""

    private fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun headerOf(message: String, name: String): String? = message.lineSequence()
        .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * A description lists several services; the control URL wanted is the one
     * inside the AVTransport block, not the first in the document.
     */
    private fun tagAfter(xml: String, marker: String, name: String): String? {
        val at = xml.indexOf(marker).takeIf { it >= 0 } ?: return null
        return tag(xml.substring(at), name)
    }

    private fun searchFor(target: String): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_HOST:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $target\r\n\r\n"

    private companion object {
        const val SSDP_HOST = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

        /** Where renderers usually publish their description. */
        val COMMON_PATHS = listOf(
            "/description.xml", "/dmr.xml", "/MediaRenderer.xml",
            "/rootDesc.xml", "/upnp/desc.xml", ":8060/dial/dd.xml"
        )

        /**
         * Asked for in three ways. Not every renderer answers the
         * MediaRenderer device type: some reply only to the AVTransport
         * service, and a few only to ssdp:all. The description then sorts
         * the wheat from the chaff.
         */
        val SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            AV_TRANSPORT,
            "ssdp:all"
        )
    }
}
