package com.mateof.kanal.cast.gcast

import com.mateof.kanal.cast.CastDevice
import com.mateof.kanal.cast.CastPosition
import com.mateof.kanal.cast.CastProtocol
import com.mateof.kanal.cast.CastSource
import com.mateof.kanal.cast.Dlna
import com.mateof.kanal.cast.LocalTls
import com.mateof.kanal.cast.Renderer
import com.mateof.kanal.cast.RendererRefused
import com.mateof.kanal.cast.msToUpnpTime
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.net.redactUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLSocket

/**
 * Sends to a Chromecast, or a television with Google Cast built in, speaking
 * the Cast protocol directly: TLS on port 8009 and JSON messages inside a
 * one-message protobuf ([CastMessage]).
 *
 * Not the Google Cast SDK on purpose. The SDK needs Google Play services,
 * which no Fire TV has, and a sender that is a television itself is exactly
 * what Kanal is half the time. What it would add — its own picker, the
 * authentication of the receiver — is not needed to play an address.
 *
 * The television plays it in Google's Default Media Receiver. That receiver
 * handles MP4, WebM, HLS and DASH, but **not** a bare MPEG-TS stream, which is
 * what most live channels are: those are sent as HLS when the source offers it.
 */
@Singleton
class GoogleCastClient @Inject constructor(
    private val logger: FileLogger
) : Renderer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channels = ConcurrentHashMap<String, Channel>()
    private val connecting = Mutex()

    suspend fun discover(timeoutMs: Int = 2_500): List<CastDevice> = withContext(Dispatchers.IO) {
        runCatching { Mdns.browse(SERVICE, timeoutMs) }
            .onFailure { logger.w("Cast", "Fallo buscando aparatos con Google Cast", it) }
            .getOrDefault(emptyList())
            .map { found ->
                deviceFor(found.address, found.port, found.txt["fn"] ?: found.instance.substringBefore("._"), found.txt["id"])
            }
            .distinctBy { it.id }
            .also { logger.i("Cast", "Google Cast: ${it.size} aparatos") }
    }

    /** Typed in by hand: there if port 8009 answers like a Cast device. */
    suspend fun describeManual(address: String): CastDevice? = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(if ("://" in address) address.trim() else "cast://${address.trim()}") }.getOrNull()
        val host = uri?.host ?: return@withContext null
        val port = uri.port.takeIf { it > 0 && it != 80 } ?: DEFAULT_PORT
        val device = deviceFor(host, port, null, null)
        runCatching {
            Channel.open(host, port, scope, logger).use { it.receiverStatus() }
            device
        }.onFailure { logger.d("Cast", "Google Cast: nada en $host:$port (${it.message})") }.getOrNull()
    }

    private fun deviceFor(host: String, port: Int, name: String?, id: String?) = CastDevice(
        name = name ?: "Google Cast ($host)",
        controlUrl = "cast://$host:$port",
        id = "gcast:${id ?: "$host:$port"}",
        udn = id ?: "gcast:$host:$port",
        protocol = CastProtocol.GOOGLE_CAST
    )

    override suspend fun play(device: CastDevice, source: CastSource, title: String, live: Boolean): Result<Unit> =
        call(device) { channel ->
            val app = channel.app(launch = true)
            val reply = channel.request(app.transport, NS_MEDIA, timeoutMs = LOAD_TIMEOUT_MS) {
                put("type", "LOAD")
                put("sessionId", app.session)
                put("autoplay", true)
                put("currentTime", 0)
                putJsonObject("media") {
                    put("contentId", source.url)
                    put("contentUrl", source.url)
                    put("streamType", if (live) "LIVE" else "BUFFERED")
                    put("contentType", contentType(source))
                    putJsonObject("metadata") {
                        put("type", 0)
                        put("metadataType", 0)
                        put("title", title)
                    }
                }
            }
            when (reply.string("type")) {
                "MEDIA_STATUS" -> channel.track(reply)
                // The receiver could not fetch or decode it. The relay may help
                // with a film, the same as a UPnP 716; a live MPEG-TS stream it
                // would refuse just the same through the phone.
                "LOAD_FAILED" -> throw RendererRefused(
                    "${device.name}: LOAD_FAILED",
                    fetchFailed = !live || contentType(source) != Dlna.MIME_TS
                )
                else -> throw RendererRefused("${device.name}: ${reply.string("type")} ${reply.string("reason").orEmpty()}".trim(), fetchFailed = false)
            }
            logger.i("Cast", "Enviado '$title' a ${device.name} por Google Cast (${source.route}): ${redactUrl(source.url)}")
        }

    /** Closes the receiver app, which takes the television back to where it was. */
    override suspend fun stop(device: CastDevice): Result<Unit> = call(device) { channel ->
        channel.runningApp()?.let { app ->
            channel.request(RECEIVER, NS_RECEIVER) {
                put("type", "STOP")
                put("sessionId", app.session)
            }
        }
        channels.remove(device.id)?.close()
        Unit
    }

    override suspend fun pause(device: CastDevice): Result<Unit> = media(device, "PAUSE")

    override suspend fun resume(device: CastDevice): Result<Unit> = media(device, "PLAY")

    override suspend fun seek(device: CastDevice, positionMs: Long): Result<Unit> = media(device, "SEEK") {
        put("currentTime", positionMs / 1_000.0)
        put("resumeState", "PLAYBACK_START")
    }

    override suspend fun transportState(device: CastDevice): Result<String?> = call(device) { channel ->
        if (channel.runningApp() == null) return@call "NO_MEDIA_PRESENT"
        val status = channel.mediaStatus() ?: return@call "STOPPED"
        upnpState(status.string("playerState"))
    }

    override suspend fun position(device: CastDevice): Result<CastPosition> = call(device) { channel ->
        val status = channel.mediaStatus() ?: throw IOException("no hay nada cargado")
        val elapsed = (status["currentTime"] as? JsonPrimitive)?.doubleOrNull
        val duration = ((status["media"] as? JsonObject)?.get("duration") as? JsonPrimitive)?.doubleOrNull
        CastPosition(
            elapsed = elapsed?.takeIf { it > 0 }?.let { msToUpnpTime((it * 1_000).toLong()) },
            duration = duration?.takeIf { it > 0 }?.let { msToUpnpTime((it * 1_000).toLong()) }
        )
    }

    private suspend fun media(device: CastDevice, type: String, extra: JsonObjectBuilder.() -> Unit = {}): Result<Unit> =
        call(device) { channel ->
            val app = channel.runningApp() ?: throw IOException("${device.name} ya no tiene nada abierto")
            val session = channel.mediaSession ?: channel.mediaStatus()?.let { channel.mediaSession }
                ?: throw IOException("${device.name} no tiene nada cargado")
            val reply = channel.request(app.transport, NS_MEDIA) {
                put("type", type)
                put("mediaSessionId", session)
                extra()
            }
            if (reply.string("type") != "MEDIA_STATUS") throw IOException("$type: ${reply.string("type")}")
            channel.track(reply)
        }

    private suspend fun <T> call(device: CastDevice, block: suspend (Channel) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            runCatching {
                val channel = connecting.withLock {
                    channels[device.id]?.takeIf { it.alive } ?: run {
                        val uri = URI(device.controlUrl)
                        Channel.open(uri.host, uri.port.takeIf { it > 0 } ?: DEFAULT_PORT, scope, logger)
                            .also { channels[device.id] = it }
                    }
                }
                try {
                    block(channel)
                } catch (failure: IOException) {
                    if (!channel.alive) channels.remove(device.id, channel)
                    throw failure
                }
            }
        }

    data class App(val transport: String, val session: String)

    /**
     * One TLS connection to one device. Replies are matched to requests by
     * requestId; the device's pings are answered from the reading thread, or
     * it hangs up after a few seconds.
     */
    private class Channel(
        private val socket: SSLSocket,
        private val logger: FileLogger
    ) : AutoCloseable {
        private val output = DataOutputStream(socket.getOutputStream())
        private val input = DataInputStream(socket.getInputStream())
        private val ids = AtomicInteger()
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
        private val connected = ConcurrentHashMap.newKeySet<String>()
        private var heartbeat: Job? = null

        @Volatile
        var alive = true
            private set

        @Volatile
        var mediaSession: Int? = null
            private set

        @Volatile
        private var app: App? = null

        fun start(scope: CoroutineScope) {
            Thread({ readLoop() }, "kanal-cast-read").apply { isDaemon = true }.start()
            connect(RECEIVER)
            heartbeat = scope.launch {
                while (isActive && alive) {
                    delay(HEARTBEAT_MS)
                    runCatching { send(RECEIVER, NS_HEARTBEAT, buildJsonObject { put("type", "PING") }) }
                }
            }
        }

        private fun connect(destination: String) {
            if (connected.add(destination)) {
                send(destination, NS_CONNECTION, buildJsonObject { put("type", "CONNECT") })
            }
        }

        @Synchronized
        fun send(destination: String, namespace: String, payload: JsonObject) {
            val bytes = CastMessage(SENDER, destination, namespace, payload.toString()).encode()
            try {
                output.writeInt(bytes.size)
                output.write(bytes)
                output.flush()
            } catch (failure: IOException) {
                shut(failure)
                throw failure
            }
        }

        suspend fun request(
            destination: String,
            namespace: String,
            timeoutMs: Long = REQUEST_TIMEOUT_MS,
            body: JsonObjectBuilder.() -> Unit
        ): JsonObject {
            if (!alive) throw IOException("conexión con Google Cast cerrada")
            val id = ids.incrementAndGet()
            val reply = CompletableDeferred<JsonObject>()
            pending[id] = reply
            try {
                send(destination, namespace, buildJsonObject {
                    put("requestId", id)
                    body()
                })
                return withTimeout(timeoutMs) { reply.await() }
            } finally {
                pending.remove(id)
            }
        }

        suspend fun receiverStatus(): JsonObject =
            request(RECEIVER, NS_RECEIVER) { put("type", "GET_STATUS") }

        /** The Default Media Receiver if it is running, connected to. */
        suspend fun runningApp(): App? {
            val status = receiverStatus()
            val running = ((status["status"] as? JsonObject)?.get("applications") as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.string("appId") == DEFAULT_RECEIVER }
            app = running?.let { found ->
                val transport = found.string("transportId") ?: return@let null
                val session = found.string("sessionId") ?: return@let null
                App(transport, session)
            }
            app?.let { connect(it.transport) } ?: run { mediaSession = null }
            return app
        }

        /** The receiver, started if [launch] and it is not running yet. */
        suspend fun app(launch: Boolean): App {
            runningApp()?.let { return it }
            if (!launch) throw IOException("el receptor no está abierto")
            val reply = request(RECEIVER, NS_RECEIVER, LAUNCH_TIMEOUT_MS) {
                put("type", "LAUNCH")
                put("appId", DEFAULT_RECEIVER)
            }
            if (reply.string("type") == "LAUNCH_ERROR") {
                throw RendererRefused("no se pudo abrir el receptor: ${reply.string("reason")}", fetchFailed = false)
            }
            // The reply may come before the app has registered its transport.
            repeat(LAUNCH_POLLS) {
                runningApp()?.let { return it }
                delay(LAUNCH_POLL_MS)
            }
            throw IOException("el receptor no llegó a abrirse")
        }

        suspend fun mediaStatus(): JsonObject? {
            val app = runningApp() ?: return null
            val reply = request(app.transport, NS_MEDIA) { put("type", "GET_STATUS") }
            return track(reply)
        }

        /** Remembers the media session a status reports; returns that status. */
        fun track(reply: JsonObject): JsonObject? {
            val status = (reply["status"] as? JsonArray)?.firstOrNull() as? JsonObject
            mediaSession = (status?.get("mediaSessionId") as? JsonPrimitive)?.intOrNull
            return status
        }

        private fun readLoop() {
            try {
                while (alive) {
                    val length = input.readInt()
                    if (length !in 0..MAX_MESSAGE) throw IOException("mensaje de Cast de $length bytes")
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    val message = CastMessage.decode(bytes)
                    val payload = runCatching { Json.parseToJsonElement(message.payload).jsonObject }.getOrNull() ?: continue
                    val type = payload.string("type")
                    when {
                        message.namespace == NS_HEARTBEAT && type == "PING" ->
                            send(message.source, NS_HEARTBEAT, buildJsonObject { put("type", "PONG") })

                        message.namespace == NS_CONNECTION && type == "CLOSE" -> {
                            connected.remove(message.source)
                            if (message.source == RECEIVER) shut(IOException("Google Cast cerró la conexión"))
                        }

                        else -> {
                            val id = (payload["requestId"] as? JsonPrimitive)?.intOrNull ?: 0
                            if (id != 0) pending[id]?.complete(payload)
                            else if (type == "MEDIA_STATUS") track(payload)
                        }
                    }
                }
            } catch (failure: Exception) {
                shut(failure)
            }
        }

        private fun shut(cause: Exception) {
            if (!alive) return
            alive = false
            heartbeat?.cancel()
            logger.d("Cast", "Conexión con Google Cast cerrada: ${cause.message}")
            val closed = IOException("conexión con Google Cast cerrada: ${cause.message}", cause)
            pending.values.forEach { it.completeExceptionally(closed) }
            runCatching { socket.close() }
        }

        override fun close() {
            runCatching { send(RECEIVER, NS_CONNECTION, buildJsonObject { put("type", "CLOSE") }) }
            shut(IOException("cerrada por Kanal"))
        }

        companion object {
            fun open(host: String, port: Int, scope: CoroutineScope, logger: FileLogger): Channel {
                val socket = LocalTls.socketFactory.createSocket() as SSLSocket
                try {
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    socket.startHandshake()
                } catch (failure: Exception) {
                    runCatching { socket.close() }
                    throw failure
                }
                return Channel(socket, logger).also { it.start(scope) }
            }
        }
    }

    companion object {
        const val SERVICE = "_googlecast._tcp.local"
        const val DEFAULT_PORT = 8009

        /** Google's Default Media Receiver: needs no registration of our own. */
        const val DEFAULT_RECEIVER = "CC1AD845"

        private const val SENDER = "sender-0"
        private const val RECEIVER = "receiver-0"
        private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val REQUEST_TIMEOUT_MS = 8_000L
        private const val LAUNCH_TIMEOUT_MS = 20_000L
        private const val LAUNCH_POLLS = 10
        private const val LAUNCH_POLL_MS = 500L

        /** A LOAD is answered once the receiver has the first of it, or has given up. */
        private const val LOAD_TIMEOUT_MS = 45_000L
        private const val HEARTBEAT_MS = 5_000L
        private const val MAX_MESSAGE = 64 * 1024

        fun upnpState(playerState: String?): String = when (playerState) {
            "PLAYING" -> "PLAYING"
            "PAUSED" -> "PAUSED_PLAYBACK"
            "BUFFERING", "LOADING" -> "TRANSITIONING"
            else -> "STOPPED"
        }

        /** What the receiver is told the address holds; it picks its player by this. */
        fun contentType(source: CastSource): String = when (source.mime) {
            Dlna.MIME_HLS -> "application/x-mpegURL"
            "video/mpeg" -> Dlna.MIME_TS
            else -> source.mime
        }

        private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    }
}
