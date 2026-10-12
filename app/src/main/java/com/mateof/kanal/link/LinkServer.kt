package com.mateof.kanal.link

import android.content.Context
import android.net.wifi.WifiManager
import com.mateof.kanal.core.log.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lets other devices with Kanal find this one and send it things.
 *
 * Two sockets: a UDP one that answers "who is there" broadcasts with a
 * [PeerInfo], and a small HTTP one for the requests themselves. Both are
 * plain sockets for the same reasons as the cast relay: a handful of tiny
 * JSON requests do not justify a server library.
 *
 * It only runs while Kanal is in the foreground, which is what "a device with
 * Kanal open" means to the person sending.
 */
@Singleton
class LinkServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identity: DeviceIdentity,
    private val receiver: LinkReceiver,
    private val logger: FileLogger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var http: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var jobs: List<Job> = emptyList()
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile
    var port: Int = 0
        private set

    @Synchronized
    fun start() {
        if (http != null) return
        val server = runCatching { bindHttp(LinkPorts.HTTP) }
            .recoverCatching { bindHttp(0) }
            .onFailure { logger.w("Link", "No se pudo abrir el puerto para otros dispositivos", it) }
            .getOrNull() ?: return
        http = server
        port = server.localPort
        val discovery = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(LinkPorts.DISCOVERY))
            }
        }.onFailure {
            logger.w("Link", "Sin descubrimiento: el puerto ${LinkPorts.DISCOVERY} está ocupado", it)
        }.getOrNull()
        udp = discovery
        // Some Wi-Fi drivers drop broadcast frames to save power unless an
        // app holds this; it is released the moment Kanal leaves the screen.
        multicastLock = runCatching {
            context.applicationContext.getSystemService(WifiManager::class.java)
                ?.createMulticastLock("kanal-link")
                ?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()

        jobs = listOfNotNull(
            scope.launch { acceptLoop(server) },
            discovery?.let { socket -> scope.launch { discoveryLoop(socket) } }
        )
        logger.i("Link", "Visible para otros dispositivos en el puerto $port")
    }

    @Synchronized
    fun stop() {
        val server = http ?: return
        http = null
        runCatching { server.close() }
        runCatching { udp?.close() }
        udp = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        port = 0
        logger.d("Link", "Ya no es visible para otros dispositivos")
    }

    private fun bindHttp(port: Int) = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (scope.isActive && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: SocketException) {
                return
            }
            scope.launch { socket.use { handle(it) } }
        }
    }

    private suspend fun discoveryLoop(socket: DatagramSocket) {
        val buffer = ByteArray(512)
        while (scope.isActive && !socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketException) {
                return
            }
            val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            if (!text.startsWith(DISCOVERY_PREFIX)) continue
            val askedBy = text.removePrefix(DISCOVERY_PREFIX).trim()
            val info = identity.info(port)
            if (askedBy == info.id) continue
            val reply = LinkJson.encodeToString(info).toByteArray(Charsets.UTF_8)
            runCatching { socket.send(DatagramPacket(reply, reply.size, packet.socketAddress)) }
        }
    }

    private suspend fun handle(socket: Socket) {
        socket.soTimeout = READ_TIMEOUT_MS
        val host = socket.inetAddress?.hostAddress.orEmpty()
        val request = runCatching { readRequest(BufferedInputStream(socket.getInputStream())) }.getOrNull()
        if (request == null) {
            respond(socket, 400, """{"ok":false}""")
            return
        }
        val (method, path, body) = request
        val result = runCatching {
            when {
                method == "GET" && path == "/kanal/v1/info" ->
                    200 to LinkJson.encodeToString(identity.info(port))

                method == "POST" && path == "/kanal/v1/play" ->
                    200 to LinkJson.encodeToString(receiver.play(LinkJson.decodeFromString<PlayCommand>(body)))

                method == "POST" && path == "/kanal/v1/stop" ->
                    200 to LinkJson.encodeToString(receiver.stop())

                method == "POST" && path == "/kanal/v1/sources" ->
                    200 to LinkJson.encodeToString(
                        receiver.offer(LinkJson.decodeFromString<SourcesOffer>(body), host)
                    )

                else -> 404 to """{"ok":false}"""
            }
        }.getOrElse { failure ->
            logger.w("Link", "Petición de $host no válida: $method $path", failure)
            400 to """{"ok":false}"""
        }
        respond(socket, result.first, result.second)
    }

    /** Request line, headers and a body of Content-Length bytes; nothing more is needed. */
    private fun readRequest(input: InputStream): Triple<String, String, String>? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        var length = 0
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val name = line.substringBefore(':').trim()
            if (name.equals("Content-Length", ignoreCase = true)) {
                length = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        if (length < 0 || length > MAX_BODY) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return Triple(parts[0].uppercase(), parts[1].substringBefore('?'), String(body, Charsets.UTF_8))
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) return out.toString("UTF-8").trimEnd('\r')
            if (out.size() > MAX_LINE) return null
            out.write(b)
        }
    }

    private fun respond(socket: Socket, code: Int, json: String) {
        val body = json.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            else -> "Bad Request"
        }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        runCatching {
            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
        }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_LINE = 8 * 1024
        const val MAX_BODY = 512 * 1024
    }
}
