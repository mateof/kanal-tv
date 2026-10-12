package com.mateof.kanal.link

import com.mateof.kanal.cast.relay.LanLink
import com.mateof.kanal.cast.relay.NetworkInspector
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.repo.Playable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The sending end: finds other devices with Kanal and asks them for things. */
@Singleton
class LinkClient @Inject constructor(
    private val prefs: AppPreferences,
    private val identity: DeviceIdentity,
    private val network: NetworkInspector,
    private val logger: FileLogger
) {
    /**
     * Its own client and not the shared one: that one logs every request, and
     * a sources offer carries passwords in its body. Nothing here goes to the
     * internet, so none of its settings apply anyway.
     */
    private val base = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** Broadcasts and listens; the devices remembered by address are asked as well. */
    suspend fun discover(timeoutMs: Long = DISCOVERY_MS): List<KanalPeer> = coroutineScope {
        val remembered = async {
            prefs.linkAddresses.first().map { address -> async { probe(address) } }.awaitAll().filterNotNull()
        }
        val broadcast = broadcast(timeoutMs)
        (broadcast + remembered.await()).distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    private suspend fun broadcast(timeoutMs: Long): List<KanalPeer> = withContext(Dispatchers.IO) {
        val selfId = prefs.linkDeviceId()
        val payload = (DISCOVERY_PREFIX + selfId).toByteArray(Charsets.UTF_8)
        val found = ConcurrentHashMap<String, KanalPeer>()
        val links = network.lanLinks()
        // One socket per network, tied to it, so a VPN that claims every
        // route cannot carry the broadcast off into the tunnel.
        val sockets = if (links.isEmpty()) {
            listOfNotNull(openSocket(null)?.let { it to listOf(LIMITED_BROADCAST) })
        } else {
            links.mapNotNull { link ->
                openSocket(link)?.let { it to listOfNotNull(broadcastOf(link), LIMITED_BROADCAST).distinct() }
            }
        }
        try {
            coroutineScope {
                for ((socket, targets) in sockets) {
                    launch { listen(socket, selfId, found, timeoutMs) }
                    launch {
                        // UDP gets lost; three tries cost nothing.
                        repeat(3) {
                            for (target in targets) {
                                runCatching {
                                    socket.send(DatagramPacket(payload, payload.size, target, LinkPorts.DISCOVERY))
                                }
                            }
                            delay(timeoutMs / 4)
                        }
                    }
                }
            }
        } finally {
            sockets.forEach { runCatching { it.first.close() } }
        }
        found.values.toList()
    }

    private fun openSocket(link: LanLink?): DatagramSocket? = runCatching {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.broadcast = true
        socket.bind(InetSocketAddress(link?.address, 0))
        link?.let { runCatching { it.network.bindSocket(socket) } }
        socket
    }.onFailure { logger.d("Link", "No se pudo abrir un socket de descubrimiento: ${it.message}") }.getOrNull()

    private fun listen(
        socket: DatagramSocket,
        selfId: String,
        found: MutableMap<String, KanalPeer>,
        timeoutMs: Long
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteArray(4096)
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return
            socket.soTimeout = left.toInt().coerceAtLeast(1)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                return
            } catch (_: IOException) {
                return
            }
            val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            val info = runCatching { LinkJson.decodeFromString<PeerInfo>(text) }.getOrNull() ?: continue
            if (info.id == selfId) continue
            val host = packet.address?.hostAddress ?: continue
            found[info.id] = KanalPeer(info, host)
        }
    }

    /** The subnet's broadcast address: 192.168.1.37/24 → 192.168.1.255. */
    private fun broadcastOf(link: LanLink): InetAddress? {
        val address = link.address as? Inet4Address ?: return null
        val bytes = address.address
        var bits = link.subnet.prefixLength.coerceIn(0, 32)
        if (bits >= 31) return null
        for (i in bytes.indices) {
            val keep = bits.coerceIn(0, 8)
            val hostMask = (0xFF shr keep) and 0xFF
            bytes[i] = (bytes[i].toInt() or hostMask).toByte()
            bits -= keep
        }
        return InetAddress.getByAddress(bytes)
    }

    /**
     * Asks one address directly, for a device typed in by hand or remembered
     * from before: "192.168.1.40" or "192.168.1.40:47820".
     */
    suspend fun probe(address: String): KanalPeer? = withContext(Dispatchers.IO) {
        val (host, port) = parseAddress(address) ?: return@withContext null
        val url = "http://${if (host.contains(':')) "[$host]" else host}:$port/kanal/v1/info"
        runCatching {
            execute(host, Request.Builder().url(url).build(), base).use { response ->
                if (!response.isSuccessful) return@use null
                val info = LinkJson.decodeFromString<PeerInfo>(response.body?.string().orEmpty())
                // The port typed is the one that answered, whatever the other
                // side believes it listens on (an emulator redirect, say).
                KanalPeer(info.copy(port = port), host)
            }
        }.onFailure { logger.d("Link", "Nadie con Kanal en $host:$port (${it.message})") }.getOrNull()
    }

    suspend fun play(peer: KanalPeer, command: PlayCommand): Result<PlayAnswer> =
        post(peer, "/kanal/v1/play", LinkJson.encodeToString(command), base) {
            LinkJson.decodeFromString<PlayAnswer>(it)
        }.mapCatching { answer ->
            if (!answer.ok) throw IOException(answer.error.ifBlank { "refused" })
            answer
        }

    suspend fun stop(peer: KanalPeer): Result<StopAnswer> =
        post(peer, "/kanal/v1/stop", "{}", base) { LinkJson.decodeFromString<StopAnswer>(it) }

    /** Waits for the other side to answer, which means someone pressing a button there. */
    suspend fun sendSources(peer: KanalPeer, sources: List<Source>): Result<SourcesAnswer> {
        val offer = SourcesOffer(identity.sender(), sources)
        val patient = base.newBuilder()
            .readTimeout(LinkInbox.OFFER_TIMEOUT_MS + 10_000, TimeUnit.MILLISECONDS)
            .build()
        return post(peer, "/kanal/v1/sources", LinkJson.encodeToString(offer), patient) {
            LinkJson.decodeFromString<SourcesAnswer>(it)
        }
    }

    /** What to send for [playable], already playing or about to be. */
    suspend fun commandFor(playable: Playable, source: Source?, positionMs: Long = 0L): PlayCommand {
        val urls = (listOf(playable.url) + playable.fallbackUrls)
            .filter(LinkReceiver::isNetworkUrl)
            .distinct()
        return PlayCommand(
            from = identity.sender(),
            kind = playable.kind.name,
            itemId = playable.itemId,
            title = playable.title,
            subtitle = playable.subtitle,
            logo = playable.logo,
            isLive = playable.isLive,
            seriesId = playable.seriesId,
            urls = urls,
            userAgent = playable.userAgent,
            positionMs = if (playable.isLive) 0L else positionMs,
            source = source?.let(LinkReceiver::keyOf)
        )
    }

    private suspend fun <T> post(
        peer: KanalPeer,
        path: String,
        json: String,
        client: OkHttpClient,
        parse: (String) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(peer.baseUrl + path)
                .post(json.toRequestBody(JSON))
                .build()
            execute(peer.host, request, client).use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                parse(response.body?.string().orEmpty())
            }
        }.onFailure { logger.w("Link", "${peer.name} no contestó a $path: ${it.message}") }
    }

    /**
     * Through the network the other device is on, never through a VPN — and
     * if that refuses to connect, the system's own route. A device with two
     * interfaces on the same range (an emulator's wlan0 behind a virtual
     * router next to eth0, or Wi-Fi and Ethernet at once) can tie the socket
     * to the one that does not get there. Only a failure to connect is
     * retried: the request never left, so nothing is sent twice.
     */
    private fun execute(host: String, request: Request, client: OkHttpClient): okhttp3.Response {
        val routed = clientFor(host, client)
        if (routed === client) return client.newCall(request).execute()
        return try {
            routed.newCall(request).execute()
        } catch (e: IOException) {
            val connecting = e is java.net.ConnectException ||
                e is java.net.NoRouteToHostException ||
                (e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true) ||
                e.message?.startsWith("failed to connect", ignoreCase = true) == true
            if (!connecting) throw e
            logger.d("Link", "Sin conexión a $host por su red (${e.message}); probando la ruta del sistema")
            client.newCall(request).execute()
        }
    }

    /** Through the network the other device is on, never through a VPN. */
    private fun clientFor(host: String, client: OkHttpClient = base): OkHttpClient {
        val target = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return client
        val link = network.lanLinkFor(target) ?: return client
        return client.newBuilder().socketFactory(link.network.socketFactory).build()
    }

    private fun parseAddress(raw: String): Pair<String, Int>? {
        var text = raw.trim().removePrefix("http://").substringBefore('/')
        if (text.isEmpty()) return null
        // [v6]:port, v4:port, or a bare host.
        if (text.startsWith('[')) {
            val host = text.substringAfter('[').substringBefore(']')
            val port = text.substringAfter("]:", "").toIntOrNull() ?: LinkPorts.HTTP
            return host to port
        }
        if (text.count { it == ':' } == 1) {
            val port = text.substringAfter(':').toIntOrNull() ?: return null
            text = text.substringBefore(':')
            return text to port
        }
        return text to LinkPorts.HTTP
    }

    private companion object {
        const val DISCOVERY_MS = 1_800L
        val LIMITED_BROADCAST: InetAddress = InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
