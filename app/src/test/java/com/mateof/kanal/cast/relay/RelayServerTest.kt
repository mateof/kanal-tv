package com.mateof.kanal.cast.relay

import com.mateof.kanal.cast.Dlna
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The relay against a real origin on the loopback interface, spoken to over a
 * raw socket so the exact headers a renderer would see can be checked.
 */
class RelayServerTest {

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val payload = ByteArray(300_000) { (it % 251).toByte() }

    private lateinit var origin: FakeOrigin
    private lateinit var relay: RelayServer
    private val originHits = AtomicInteger()
    private val originAgents = Collections.synchronizedList(mutableListOf<String?>())
    private val originRanges = Collections.synchronizedList(mutableListOf<String?>())
    private val unreachable = Collections.synchronizedList(mutableListOf<IOException>())
    private val unsupported = Collections.synchronizedList(mutableListOf<String>())

    private val events = object : RelayServer.Events {
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun upstreamUnreachable(error: IOException) {
            unreachable += error
        }
        override fun unsupported(contentType: String) {
            unsupported += contentType
        }
    }

    @Before
    fun setUp() {
        origin = FakeOrigin(loopback) { method, path, headers ->
            when (path) {
                // Unknown length, like a live channel: sent chunked, and the
                // relay must pass it on as a plain body ended by the close.
                "/live/u/p/1.ts" -> {
                    originHits.incrementAndGet()
                    originAgents += headers["user-agent"]
                    FakeOrigin.Answer(200, listOf("Content-Type" to "video/mp2t"), payload, chunked = true)
                }
                "/movie/u/p/2.mp4" -> {
                    originHits.incrementAndGet()
                    val range = headers["range"]
                    originRanges += range
                    if (range != null) {
                        val from = range.removePrefix("bytes=").substringBefore('-').toInt()
                        val slice = payload.copyOfRange(from, payload.size)
                        FakeOrigin.Answer(
                            206,
                            listOf(
                                "Content-Type" to "video/mp4",
                                "Accept-Ranges" to "bytes",
                                "Content-Range" to "bytes $from-${payload.size - 1}/${payload.size}",
                                "Content-Length" to slice.size.toString()
                            ),
                            slice
                        )
                    } else {
                        FakeOrigin.Answer(
                            200,
                            listOf(
                                "Content-Type" to "video/mp4",
                                "Accept-Ranges" to "bytes",
                                "Content-Length" to payload.size.toString()
                            ),
                            if (method == "HEAD") ByteArray(0) else payload
                        )
                    }
                }
                "/movie/u/p/3.mkv" ->
                    if (method == "HEAD") {
                        FakeOrigin.Answer(405, listOf("Content-Length" to "0"), ByteArray(0))
                    } else {
                        FakeOrigin.Answer(200, listOf("Content-Length" to payload.size.toString()), payload)
                    }
                "/hls.m3u8" -> FakeOrigin.Answer(
                    200,
                    listOf("Content-Type" to "application/vnd.apple.mpegurl", "Content-Length" to "8"),
                    "#EXTM3U\n".toByteArray()
                )
                else -> FakeOrigin.Answer(404, listOf("Content-Length" to "0"), ByteArray(0))
            }
        }
        relay = RelayServer(loopback, 0, OkHttpClient(), events).apply { start() }
    }

    @After
    fun tearDown() {
        relay.stop()
        origin.close()
    }

    private fun originUrl(path: String) = "http://127.0.0.1:${origin.port}$path"

    private fun open(
        path: String,
        live: Boolean = true,
        client: InetAddress = loopback,
        mime: String = Dlna.MIME_TS
    ): RelaySession = RelaySession(
        token = RelayTokens.issue(),
        upstreamUrl = originUrl(path),
        userAgent = "KanalTest/1.0",
        mime = mime,
        features = if (live) Dlna.LIVE_FEATURES else Dlna.SEEKABLE_FEATURES,
        live = live,
        client = client
    ).also { relay.session = it }

    private class Reply(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun ask(method: String, path: String, vararg headers: Pair<String, String>): Reply {
        Socket(loopback, relay.port).use { socket ->
            val request = buildString {
                append("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n")
                headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray())
            val all = ByteArrayOutputStream()
            socket.getInputStream().copyTo(all)
            val bytes = all.toByteArray()
            val split = String(bytes, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            val head = String(bytes, 0, split, Charsets.ISO_8859_1).split("\r\n")
            return Reply(
                status = head.first().split(' ')[1].toInt(),
                headers = head.drop(1).associate {
                    it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
                },
                body = bytes.copyOfRange(split + 4, bytes.size)
            )
        }
    }

    private fun pathOf(session: RelaySession) = RelayTokens.path(session.token, ".ts")

    @Test
    fun servesALiveStreamWithTheHeadersDlnaRenderersWant() {
        val session = open("/live/u/p/1.ts")
        val reply = ask("GET", pathOf(session), "getcontentFeatures.dlna.org" to "1")

        assertEquals(200, reply.status)
        assertEquals("video/mp2t", reply.headers["content-type"])
        assertEquals("Streaming", reply.headers["transfermode.dlna.org"])
        assertEquals(Dlna.LIVE_FEATURES, reply.headers["contentfeatures.dlna.org"])
        assertEquals("close", reply.headers["connection"])
        assertNull(reply.headers["transfer-encoding"])
        assertNull(reply.headers["content-length"])
        assertArrayEquals(payload, reply.body)
        assertEquals(listOf<String?>("KanalTest/1.0"), originAgents.toList())
    }

    @Test
    fun theUrlCarriesTheTokenAndTheExtensionButNoCredentials() {
        val session = open("/live/u/p/1.ts")
        val url = relay.url(session)
        assertEquals("http://127.0.0.1:${relay.port}/cast/${session.token}.ts", url)
        assertFalse(url.contains("/u/p/"))
    }

    @Test
    fun answersHeadForLiveWithoutOpeningTheChannel() {
        val session = open("/live/u/p/1.ts")
        val reply = ask("HEAD", pathOf(session))

        assertEquals(200, reply.status)
        assertEquals("video/mp2t", reply.headers["content-type"])
        assertEquals(Dlna.LIVE_FEATURES, reply.headers["contentfeatures.dlna.org"])
        assertEquals(0, reply.body.size)
        assertEquals(0, originHits.get())
    }

    @Test
    fun forwardsRangeAndLengthForAFilm() {
        val session = open("/movie/u/p/2.mp4", live = false, mime = "video/mp4")
        val reply = ask("GET", RelayTokens.path(session.token, ".mp4"), "Range" to "bytes=1000-")

        assertEquals(206, reply.status)
        assertEquals("bytes=1000-", originRanges.single())
        assertEquals("bytes 1000-${payload.size - 1}/${payload.size}", reply.headers["content-range"])
        assertEquals((payload.size - 1000).toString(), reply.headers["content-length"])
        assertEquals("bytes", reply.headers["accept-ranges"])
        assertEquals(Dlna.SEEKABLE_FEATURES, reply.headers["contentfeatures.dlna.org"])
        assertArrayEquals(payload.copyOfRange(1000, payload.size), reply.body)
    }

    @Test
    fun headForAFilmAsksTheOriginForItsLength() {
        val session = open("/movie/u/p/2.mp4", live = false, mime = "video/mp4")
        val reply = ask("HEAD", RelayTokens.path(session.token, ".mp4"))

        assertEquals(200, reply.status)
        assertEquals(payload.size.toString(), reply.headers["content-length"])
        assertEquals(0, reply.body.size)
    }

    @Test
    fun anOriginThatRefusesHeadDoesNotPutTheTelevisionOff() {
        val session = open("/movie/u/p/3.mkv", live = false, mime = "video/x-matroska")
        val reply = ask("HEAD", RelayTokens.path(session.token, ".mkv"))

        assertEquals(200, reply.status)
        assertEquals("video/x-matroska", reply.headers["content-type"])
        assertEquals(Dlna.SEEKABLE_FEATURES, reply.headers["contentfeatures.dlna.org"])
    }

    @Test
    fun refusesAnyoneButTheTelevision() {
        val session = open("/live/u/p/1.ts", client = InetAddress.getByName("192.168.1.137"))
        assertEquals(403, ask("GET", pathOf(session)).status)
        assertEquals(0, originHits.get())
    }

    @Test
    fun refusesAWrongOrMissingToken() {
        open("/live/u/p/1.ts")
        assertEquals(404, ask("GET", RelayTokens.path(RelayTokens.issue(), ".ts")).status)
        assertEquals(404, ask("GET", "/cast/").status)
        assertEquals(404, ask("GET", "/live/u/p/1.ts").status)
        assertEquals(0, originHits.get())
    }

    @Test
    fun anOldTokenStopsWorkingWhenANewSessionStarts() {
        val old = open("/live/u/p/1.ts")
        open("/live/u/p/1.ts")
        assertEquals(404, ask("GET", pathOf(old)).status)
    }

    @Test
    fun refusesOtherMethods() {
        val session = open("/live/u/p/1.ts")
        assertEquals(405, ask("POST", pathOf(session)).status)
    }

    @Test
    fun passesOnTheOriginsRefusal() {
        val session = open("/missing")
        assertEquals(404, ask("GET", pathOf(session)).status)
        assertTrue(unreachable.isEmpty())
    }

    @Test
    fun refusesARealHlsManifestAndSaysSo() {
        val session = open("/hls.m3u8")
        assertEquals(502, ask("GET", pathOf(session)).status)
        assertEquals(listOf("application/vnd.apple.mpegurl"), unsupported.toList())
    }

    @Test
    fun reportsAnOriginItCannotReach() {
        val closedPort = ServerSocket(0, 1, loopback).use { it.localPort }
        val session = RelaySession(
            token = RelayTokens.issue(),
            upstreamUrl = "http://127.0.0.1:$closedPort/live/u/p/1.ts",
            userAgent = "KanalTest/1.0",
            mime = Dlna.MIME_TS,
            features = Dlna.LIVE_FEATURES,
            live = true,
            client = loopback
        )
        relay.session = session
        assertEquals(502, ask("GET", pathOf(session)).status)
        assertEquals(1, unreachable.size)
    }

    @Test
    fun stoppingClosesThePort() {
        val port = relay.port
        relay.stop()
        val refused = runCatching { Socket(loopback, port).close() }.isFailure
        assertTrue(refused)
    }

    @Test
    fun countsIdleTimeOnlyWhileNothingIsConnected() {
        val session = open("/live/u/p/1.ts")
        ask("GET", pathOf(session))
        // The server's thread may still be closing its side of the connection.
        val deadline = System.currentTimeMillis() + 2_000
        while (relay.idleMs(System.currentTimeMillis() + 1) == 0L && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        val now = System.currentTimeMillis()
        assertTrue(relay.idleMs(now) in 0..5_000)
        assertTrue(relay.idleMs(now + 60_000) >= 60_000)
    }
}

/**
 * Just enough of an HTTP server to stand in for a provider. The JDK's own is not
 * on the classpath of Android unit tests.
 */
private class FakeOrigin(
    address: InetAddress,
    private val answer: (method: String, path: String, headers: Map<String, String>) -> Answer
) : AutoCloseable {

    class Answer(
        val status: Int,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
        val chunked: Boolean = false
    )

    private val server = ServerSocket(0, 8, address)
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { socket.use { serve(it) } }
            }
        }
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return
        val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val (method, path) = requestLine.split(' ')
        val reply = answer(method, path, headers)
        val out = socket.getOutputStream()
        val head = buildString {
            append("HTTP/1.1 ${reply.status} X\r\n")
            reply.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            if (reply.chunked) append("Transfer-Encoding: chunked\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (method == "HEAD") return
        if (reply.chunked) {
            reply.body.toList().chunked(10_000).forEach { piece ->
                out.write("${Integer.toHexString(piece.size)}\r\n".toByteArray())
                out.write(piece.toByteArray())
                out.write("\r\n".toByteArray())
            }
            out.write("0\r\n\r\n".toByteArray())
        } else {
            out.write(reply.body)
        }
        out.flush()
    }

    override fun close() = server.close()
}
