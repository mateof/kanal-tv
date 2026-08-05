package com.mateof.kanal.cast

import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.net.HttpProvider
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
    val id: String
)

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
                val payload = SEARCH.toByteArray()
                val group = InetAddress.getByName(SSDP_HOST)
                // Sent more than once: SSDP runs over UDP and a single datagram
                // going missing is normal on a busy wireless network.
                repeat(2) {
                    socket.send(DatagramPacket(payload, payload.size, group, SSDP_PORT))
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

        logger.i("Cast", "SSDP: ${locations.size} respuestas")
        locations.mapNotNull { describe(it) }
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
                id = location
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

    /** Points the renderer at [url] and starts it. */
    suspend fun play(device: CastDevice, url: String, title: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                soap(device, "SetAVTransportURI", buildString {
                    append("<InstanceID>0</InstanceID>")
                    append("<CurrentURI>").append(escape(url)).append("</CurrentURI>")
                    append("<CurrentURIMetaData>")
                    append(escape(metadata(url, title)))
                    append("</CurrentURIMetaData>")
                })
                soap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
                logger.i("Cast", "Enviado '$title' a ${device.name}")
            }
        }

    suspend fun stop(device: CastDevice): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { soap(device, "Stop", "<InstanceID>0</InstanceID>") }
    }

    private fun soap(device: CastDevice, action: String, body: String) {
        val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:$action xmlns:u="$AV_TRANSPORT">$body</u:$action></s:Body></s:Envelope>"""

        val request = Request.Builder()
            .url(device.controlUrl)
            .addHeader("SOAPAction", "\"$AV_TRANSPORT#$action\"")
            .post(envelope.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()

        http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("$action devolvió ${response.code}")
            }
        }
    }

    /** Minimal DIDL-Lite; renderers that ignore metadata still play the URL. */
    private fun metadata(url: String, title: String): String =
        """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<item id="0" parentID="-1" restricted="1">""" +
            """<dc:title>${escape(title)}</dc:title>""" +
            """<upnp:class>object.item.videoItem</upnp:class>""" +
            """<res protocolInfo="http-get:*:video/mpeg:*">${escape(url)}</res>""" +
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

    private companion object {
        const val SSDP_HOST = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

        /** Where renderers usually publish their description. */
        val COMMON_PATHS = listOf(
            "/description.xml", "/dmr.xml", "/MediaRenderer.xml",
            "/rootDesc.xml", "/upnp/desc.xml", ":8060/dial/dd.xml"
        )

        val SEARCH = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 2\r\n")
            append("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n")
        }
    }
}
