package com.mateof.kanal.cast.relay

import com.mateof.kanal.cast.Dlna
import com.mateof.kanal.core.formatBytes
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** What the relay serves and to whom, for one send to one television. */
data class RelaySession(
    val token: String,
    /** The provider's address. Carries credentials: never logged unredacted, never sent on. */
    val upstreamUrl: String,
    val userAgent: String,
    /** Served as Content-Type; must match the protocolInfo the renderer was given. */
    val mime: String,
    val features: String,
    val live: Boolean,
    /** The only address allowed to ask. */
    val client: InetAddress
)

/**
 * A minimal HTTP server that hands a television the stream the phone can reach
 * and the television cannot — a panel behind a VPN, typically.
 *
 * Written by hand on purpose, rather than with NanoHTTPD or Ktor. DLNA
 * renderers want a live stream with no length, no chunked encoding and the
 * connection closed at the end; NanoHTTPD 2.3.1 sends either chunked or a
 * literal `Content-Length: -1` for a body of unknown size, and Ktor would add
 * megabytes to serve one client. What is left is a request line, a few headers
 * and a copy loop.
 *
 * Bound to the phone's address on the television's network only — never the
 * wildcard, never the VPN interface — and it answers only the television's
 * address, with the session's token.
 */
class RelayServer(
    private val bindAddress: InetAddress,
    private val requestedPort: Int,
    private val http: OkHttpClient,
    private val events: Events
) {
    interface Events {
        fun info(message: String)
        fun warn(message: String)

        /** The provider could not be reached at all: not a television hanging up. */
        fun upstreamUnreachable(error: IOException)

        /** The provider answered with something a single url cannot carry. */
        fun unsupported(contentType: String)
    }

    @Volatile
    var session: RelaySession? = null

    private var server: ServerSocket? = null
    private val active = AtomicInteger(0)
    private val lastActivity = AtomicLong(System.currentTimeMillis())
    private val sockets: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())

    /** The GET currently being served; a new one from the television replaces it. */
    @Volatile
    private var streaming: Pair<Socket, Call>? = null

    val port: Int get() = server?.localPort ?: -1

    fun start() {
        val socket = ServerSocket(requestedPort, BACKLOG, bindAddress)
        server = socket
        lastActivity.set(System.currentTimeMillis())
        thread(name = "kanal-relay", isDaemon = true) { acceptLoop(socket) }
    }

    fun url(session: RelaySession): String {
        val host = bindAddress.hostAddress.orEmpty().substringBefore('%')
            .let { if (bindAddress is Inet6Address) "[$it]" else it }
        return "http://$host:$port" + RelayTokens.path(session.token, Dlna.extensionOf(session.mime))
    }

    /** How long nothing has been connected; zero while the television is reading. */
    fun idleMs(now: Long = System.currentTimeMillis()): Long =
        if (active.get() > 0) 0 else now - lastActivity.get()

    fun stop() {
        session = null
        runCatching { server?.close() }
        streaming?.second?.cancel()
        streaming = null
        synchronized(sockets) {
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            thread(name = "kanal-relay-conn", isDaemon = true) { serve(client) }
        }
    }

    private class Exchange(
        var method: String = "?",
        var range: String? = null,
        var code: Int = 0,
        var bytes: Long = 0,
        var ending: String = ""
    )

    private class HttpRequest(val method: String, val path: String, val headers: Map<String, String>)

    private fun serve(client: Socket) {
        sockets += client
        active.incrementAndGet()
        lastActivity.set(System.currentTimeMillis())
        val started = System.nanoTime()
        val exchange = Exchange()
        try {
            if (active.get() > MAX_CONNECTIONS) {
                exchange.code = 503
                respondEmpty(client.getOutputStream(), 503)
                return
            }
            client.soTimeout = HEADER_TIMEOUT_MS
            val request = readRequest(client.getInputStream())
            if (request == null) {
                exchange.code = 400
                respondEmpty(client.getOutputStream(), 400)
                return
            }
            exchange.method = request.method
            exchange.range = request.headers["range"]
            handle(client, request, exchange)
        } catch (e: IOException) {
            if (exchange.ending.isEmpty()) exchange.ending = e.message ?: e.javaClass.simpleName
        } finally {
            active.decrementAndGet()
            lastActivity.set(System.currentTimeMillis())
            sockets -= client
            runCatching { client.close() }
            val ms = (System.nanoTime() - started) / 1_000_000
            if (exchange.code != 0) {
                events.info(
                    "TV ${exchange.method}" +
                        (exchange.range?.let { " Range=$it" } ?: "") +
                        " → ${exchange.code} · ${formatBytes(exchange.bytes)} · $ms ms" +
                        (if (exchange.ending.isNotEmpty()) " · ${exchange.ending}" else "")
                )
            }
        }
    }

    private fun handle(client: Socket, request: HttpRequest, exchange: Exchange) {
        val out = client.getOutputStream()
        val current = session
        if (current == null || client.inetAddress != current.client) {
            // Logged with the address: a refusal here is either a second device
            // on the network or a television that answers from another address.
            exchange.code = 403
            exchange.ending = "petición de ${client.inetAddress.hostAddress}, no de la tele"
            respondEmpty(out, 403)
            return
        }
        if (request.method != "GET" && request.method != "HEAD") {
            exchange.code = 405
            respondEmpty(out, 405)
            return
        }
        if (!RelayTokens.matches(current.token, RelayTokens.fromPath(request.path))) {
            exchange.code = 404
            exchange.ending = "token no válido"
            respondEmpty(out, 404)
            return
        }
        if (request.method == "HEAD" && current.live) {
            // Answered without asking the provider: opening the channel just to
            // describe it costs a connection, and Dispatcharr tears a channel
            // down the moment its last viewer leaves, so the GET that follows
            // would land on a channel that is still stopping.
            exchange.code = 200
            writeHead(out, 200, dlnaHeaders(current))
            return
        }
        proxy(client, out, request, current, exchange)
    }

    private fun proxy(
        client: Socket,
        out: OutputStream,
        request: HttpRequest,
        current: RelaySession,
        exchange: Exchange
    ) {
        val head = request.method == "HEAD"
        val upstream = Request.Builder()
            .url(current.upstreamUrl)
            .header("User-Agent", current.userAgent)
            .apply {
                request.headers["range"]?.let { header("Range", it) }
                if (head) head()
            }
            .build()
        val call = http.newCall(upstream)
        if (!head) {
            // A television that opens a new GET has given up on the old one; the
            // old one would otherwise go on holding one of the account's few
            // connections to the provider.
            val previous = streaming
            streaming = client to call
            previous?.let { (socket, oldCall) ->
                oldCall.cancel()
                runCatching { socket.close() }
            }
        }

        val response = try {
            call.execute()
        } catch (e: IOException) {
            exchange.code = 502
            exchange.ending = "el móvil no llega al servidor: ${e.javaClass.simpleName}"
            respondEmpty(out, 502)
            // After answering: being told stops this very server.
            if (!call.isCanceled()) events.upstreamUnreachable(e)
            return
        }

        response.use { reply ->
            if (head && !reply.isSuccessful) {
                // Plenty of panels refuse HEAD on a film they serve happily on
                // GET. Passing that refusal on would make the television give
                // up before it ever asked for the file.
                exchange.code = 200
                exchange.ending = "el servidor no admite HEAD (${reply.code})"
                writeHead(out, 200, dlnaHeaders(current))
                return
            }
            if (!reply.isSuccessful) {
                exchange.code = if (reply.code in 400..499) reply.code else 502
                exchange.ending = "el servidor respondió ${reply.code}"
                respondEmpty(out, exchange.code)
                return
            }
            val type = reply.header("Content-Type").orEmpty()
            if (type.contains("mpegurl", ignoreCase = true)) {
                // A real HLS manifest lists segments by their own addresses,
                // which the television cannot reach either. Rewriting playlists
                // is out of scope; saying so clearly is not.
                exchange.code = 502
                exchange.ending = "el servidor devolvió un manifiesto HLS ($type)"
                respondEmpty(out, 502)
                events.unsupported(type)
                return
            }

            val headers = dlnaHeaders(current).toMutableList()
            reply.header("Content-Length")?.let { headers += "Content-Length" to it }
            reply.header("Content-Range")?.let { headers += "Content-Range" to it }
            if (!current.live && (reply.code == 206 || reply.header("Accept-Ranges") == "bytes")) {
                headers += "Accept-Ranges" to "bytes"
            }
            val status = if (reply.code == 206) 206 else 200
            exchange.code = status
            writeHead(out, status, headers)
            if (head) return

            val body = reply.body ?: return
            client.soTimeout = 0
            copy(body.byteStream(), out, exchange, call)
        }
    }

    /**
     * Reads and writes are told apart because they mean different things: a
     * write failing is the television hanging up, which is routine; a read
     * failing is the provider cutting the stream.
     */
    private fun copy(input: InputStream, out: OutputStream, exchange: Exchange, call: Call) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (e: IOException) {
                exchange.ending = if (call.isCanceled()) "sustituida por otra petición" else "el servidor cortó: ${e.message}"
                if (!call.isCanceled()) events.warn("El servidor cortó la emisión a mitad: ${e.message}")
                return
            }
            if (read < 0) {
                exchange.ending = "fin de la emisión"
                return
            }
            try {
                out.write(buffer, 0, read)
                // Live video: whatever arrived goes out now, not when a buffer fills.
                out.flush()
            } catch (_: IOException) {
                exchange.ending = "la tele cerró la conexión"
                call.cancel()
                return
            }
            exchange.bytes += read
            lastActivity.set(System.currentTimeMillis())
        }
    }

    private fun dlnaHeaders(current: RelaySession): List<Pair<String, String>> = listOf(
        "Content-Type" to current.mime,
        "transferMode.dlna.org" to "Streaming",
        "contentFeatures.dlna.org" to current.features,
        "Cache-Control" to "no-cache",
        "Server" to SERVER_NAME
    )

    private fun writeHead(out: OutputStream, status: Int, headers: List<Pair<String, String>>) {
        val text = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            // No length for a live stream and no chunked encoding either: the
            // end of the body is the end of the connection, which is what DLNA
            // renderers handle best.
            append("Connection: close\r\n\r\n")
        }
        out.write(text.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun respondEmpty(out: OutputStream, status: Int) {
        runCatching { writeHead(out, status, listOf("Content-Length" to "0", "Server" to SERVER_NAME)) }
    }

    private fun readRequest(input: InputStream): HttpRequest? {
        val raw = StringBuilder()
        while (true) {
            if (raw.length >= MAX_HEADER_BYTES) return null
            val b = input.read()
            if (b < 0) return null
            raw.append(b.toChar())
            // CRLF CRLF ends the headers; a bare LF LF is accepted as well.
            if (b == '\n'.code && (raw.endsWith("\r\n\r\n") || raw.endsWith("\n\n"))) break
        }
        val lines = raw.lines().map { it.trimEnd('\r') }
        val parts = lines.firstOrNull()?.split(' ')?.takeIf { it.size >= 2 } ?: return null
        val headers = lines.drop(1)
            .filter { ':' in it }
            .associate { it.substringBefore(':').trim().lowercase(Locale.ROOT) to it.substringAfter(':').trim() }
        return HttpRequest(parts[0].uppercase(Locale.ROOT), parts[1], headers)
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    private companion object {
        const val BACKLOG = 8
        const val MAX_CONNECTIONS = 6
        const val HEADER_TIMEOUT_MS = 10_000
        const val MAX_HEADER_BYTES = 16 * 1024
        const val BUFFER_BYTES = 64 * 1024
        const val SERVER_NAME = "Kanal/relay UPnP/1.0 DLNADOC/1.50"
    }
}
