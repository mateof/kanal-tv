package com.mateof.kanal.cast.webos

import com.mateof.kanal.cast.CastDevice
import com.mateof.kanal.cast.CastPosition
import com.mateof.kanal.cast.CastProtocol
import com.mateof.kanal.cast.CastSource
import com.mateof.kanal.cast.LocalTls
import com.mateof.kanal.cast.Renderer
import com.mateof.kanal.cast.RendererRefused
import com.mateof.kanal.cast.UpnpClient
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.net.redactUrl
import com.mateof.kanal.data.prefs.AppPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends to an LG television through webOS's own remote-control protocol
 * (SSAP: JSON over a WebSocket on port 3001, or 3000 on older sets).
 *
 * The television plays the address in its own media viewer rather than its
 * DLNA renderer, which is the point: the webOS sets this was written for
 * refuse a Dispatcharr address over DLNA with 716 and nothing to go on.
 *
 * The first connection has to be accepted on the television, which hands
 * back a key that is kept for the next ones ([AppPreferences.webOsKey]).
 */
@Singleton
class WebOsClient @Inject constructor(
    private val upnp: UpnpClient,
    private val prefs: AppPreferences,
    private val logger: FileLogger
) : Renderer {

    private val client = OkHttpClient.Builder()
        .sslSocketFactory(LocalTls.socketFactory, LocalTls.trustAll)
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val sessions = ConcurrentHashMap<String, Session>()
    private val connecting = Mutex()

    /** The media viewer opened on each television, so it can be closed again. */
    private val opened = ConcurrentHashMap<String, Pair<String, String>>()

    private val _pairing = MutableStateFlow<String?>(null)

    /** The television waiting for its owner to accept Kanal, for the sheets to say so. */
    val pairing: StateFlow<String?> = _pairing.asStateFlow()

    suspend fun discover(timeoutMs: Int = 3_000): List<CastDevice> {
        val replies = upnp.search(listOf(SEARCH_TARGET), timeoutMs)
            .filter { it.target.equals(SEARCH_TARGET, ignoreCase = true) }
        return replies.distinctBy { hostOf(it.location) }.mapNotNull { reply ->
            val host = hostOf(reply.location) ?: return@mapNotNull null
            val (name, udn) = upnp.identity(reply.location) ?: (null to null)
            deviceFor(host, name, udn ?: reply.usn?.substringBefore("::"))
        }.also { logger.i("Cast", "webOS: ${it.size} teles LG") }
    }

    /** An LG television typed in by address: there if its control port opens. */
    suspend fun describeManual(address: String): CastDevice? {
        val uri = runCatching { URI(address.trim().let { if ("://" in it) it else "http://$it" }) }.getOrNull()
        val host = uri?.host ?: return null
        // An address with some other port is somebody else's: a DLNA description, a Chromecast.
        if (uri.port > 0 && uri.port != PORT_PLAIN && uri.port != PORT_TLS) return null
        val device = deviceFor(host, null, null)
        return runCatching { open(device).close() }
            .onFailure { logger.d("Cast", "webOS: nada en $host (${it.message})") }
            .map { device }
            .getOrNull()
    }

    private fun deviceFor(host: String, name: String?, udn: String?) = CastDevice(
        name = name ?: "LG webOS ($host)",
        controlUrl = "ws://$host:$PORT_PLAIN/",
        id = "webos:$host",
        udn = udn ?: "webos:$host",
        protocol = CastProtocol.WEBOS
    )

    override suspend fun play(device: CastDevice, source: CastSource, title: String, live: Boolean): Result<Unit> =
        call(device) { session ->
            val reply = session.request(
                "ssap://media.viewer/open",
                buildJsonObject {
                    put("target", source.url)
                    put("title", title)
                    put("description", "")
                    put("mimeType", source.mime)
                    put("iconSrc", "")
                    put("loop", false)
                }
            )
            val app = reply.string("id")
            val sessionId = reply.string("sessionId")
            if (app != null && sessionId != null) opened[device.id] = app to sessionId
            logger.i("Cast", "Enviado '$title' a ${device.name} por webOS (${source.route}): ${redactUrl(source.url)}")
        }

    override suspend fun stop(device: CastDevice): Result<Unit> = call(device) { session ->
        val media = opened.remove(device.id)
        if (media != null) {
            session.request(
                "ssap://media.viewer/close",
                buildJsonObject {
                    put("id", media.first)
                    put("sessionId", media.second)
                }
            )
        } else {
            session.request("ssap://media.controls/stop")
        }
    }

    override suspend fun pause(device: CastDevice): Result<Unit> =
        call(device) { it.request("ssap://media.controls/pause") }

    override suspend fun resume(device: CastDevice): Result<Unit> =
        call(device) { it.request("ssap://media.controls/play") }

    /** Not every webOS version has it; a refusal ends up among the panel's incidents. */
    override suspend fun seek(device: CastDevice, positionMs: Long): Result<Unit> = call(device) {
        it.request("ssap://media.controls/seek", buildJsonObject { put("position", positionMs) })
    }

    /**
     * From what the television's media service says is in the foreground.
     * Sets that do not offer it are asked which app is in front instead:
     * the media viewer still there counts as playing.
     */
    override suspend fun transportState(device: CastDevice): Result<String?> = call(device) { session ->
        val media = runCatching { session.request("ssap://com.webos.media/getForegroundAppInfo") }.getOrNull()
        val playing = (media?.get("foregroundAppInfo") as? JsonArray)?.firstOrNull() as? JsonObject
        val state = playing?.let { (it["playState"] as? JsonPrimitive)?.contentOrNull }
        when {
            state != null -> upnpState(state)
            else -> {
                val front = session.request("ssap://com.webos.applicationManager/getForegroundAppInfo").string("appId")
                val ours = opened[device.id]?.first
                if (ours != null && front == ours) "TRANSITIONING" else "STOPPED"
            }
        }
    }

    /** SSAP has no way to ask where the media viewer is. */
    override suspend fun position(device: CastDevice): Result<CastPosition> =
        Result.failure(UnsupportedOperationException("webOS no informa de la posición"))

    /** Runs [block] on a registered connection; a broken one is dropped and opened again next time. */
    private suspend fun <T> call(device: CastDevice, block: suspend (Session) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            runCatching {
                val session = registered(device)
                try {
                    block(session)
                } catch (broken: SessionClosed) {
                    sessions.remove(device.id, session)
                    throw broken
                }
            }
        }

    private suspend fun registered(device: CastDevice): Session = connecting.withLock {
        sessions[device.id]?.takeIf { it.alive }?.let { return it }
        val session = open(device)
        try {
            register(device, session)
        } catch (failure: Exception) {
            session.close()
            throw failure
        }
        sessions[device.id] = session
        session
    }

    /** wss on 3001 first: recent firmware has closed the plain port. */
    private suspend fun open(device: CastDevice): Session {
        var last: Exception? = null
        for (url in listOf("wss://${device.host}:$PORT_TLS/", "ws://${device.host}:$PORT_PLAIN/")) {
            try {
                return Session.connect(client, url)
            } catch (failure: Exception) {
                last = failure
            }
        }
        throw IOException("${device.name} no abre el control de webOS: ${last?.message}", last)
    }

    private suspend fun register(device: CastDevice, session: Session) {
        val key = prefs.webOsKey(device.udn)
        val payload = buildJsonObject {
            REGISTRATION.forEach { (name, value) -> put(name, value) }
            if (key != null) put("client-key", key)
        }
        val replies = session.send("register", null, payload)
        try {
            withTimeout(PAIRING_TIMEOUT_MS) {
                while (true) {
                    val message = replies.receive()
                    when (message.string("type")) {
                        "registered" -> {
                            val granted = (message["payload"] as? JsonObject)?.string("client-key")
                            if (granted != null && granted != key) {
                                prefs.setWebOsKey(device.udn, granted)
                                logger.i("Cast", "${device.name} aceptó a Kanal")
                            }
                            return@withTimeout
                        }
                        "error" -> {
                            if (key != null) prefs.setWebOsKey(device.udn, null)
                            throw RendererRefused("${device.name} no aceptó a Kanal: ${message.string("error")}", fetchFailed = false)
                        }
                        else -> if ((message["payload"] as? JsonObject)?.string("pairingType") == "PROMPT") {
                            logger.i("Cast", "${device.name} pide confirmación en pantalla")
                            _pairing.value = device.name
                        }
                    }
                }
            }
        } finally {
            _pairing.value = null
            session.forget("register")
        }
    }

    private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }

    /** One WebSocket to one television, with replies matched to requests by id. */
    private class Session(private val socket: WebSocket, private val listener: Listener) {
        private val ids = AtomicInteger()

        val alive: Boolean get() = !listener.closed

        fun send(type: String, uri: String?, payload: JsonObject?, id: String = type): Channel<JsonObject> {
            val inbox = Channel<JsonObject>(Channel.UNLIMITED)
            listener.waiting[id] = inbox
            val message = buildJsonObject {
                put("type", type)
                put("id", id)
                uri?.let { put("uri", it) }
                payload?.let { put("payload", it) }
            }
            if (!socket.send(message.toString())) {
                listener.waiting.remove(id)
                throw SessionClosed("no se pudo escribir en la conexión")
            }
            return inbox
        }

        fun forget(id: String) {
            listener.waiting.remove(id)
        }

        /** The reply's payload, or the television's refusal as an exception. */
        suspend fun request(uri: String, payload: JsonObject? = null): JsonObject {
            val id = "k" + ids.incrementAndGet()
            val inbox = send("request", uri, payload, id)
            val reply = try {
                withTimeout(REQUEST_TIMEOUT_MS) { inbox.receive() }
            } finally {
                forget(id)
            }
            val body = reply["payload"] as? JsonObject ?: JsonObject(emptyMap())
            if (reply.string("type") == "error") {
                throw IOException("$uri: ${reply.string("error") ?: "error"}")
            }
            if ((body["returnValue"] as? JsonPrimitive)?.booleanOrNull == false) {
                throw IOException("$uri: ${body.string("errorText") ?: body.string("errorCode") ?: "rechazado"}")
            }
            return body
        }

        fun close() {
            socket.close(1000, null)
        }

        companion object {
            suspend fun connect(client: OkHttpClient, url: String): Session {
                val listener = Listener()
                // No Origin header: webOS refuses the upgrade when there is one
                // it does not know, and OkHttp sends none of its own.
                val socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
                try {
                    withTimeout(CONNECT_TIMEOUT_MS) { listener.opened.await() }
                } catch (failure: Exception) {
                    socket.cancel()
                    throw failure
                }
                return Session(socket, listener)
            }
        }
    }

    private class Listener : WebSocketListener() {
        val opened = CompletableDeferred<Unit>()
        val waiting = ConcurrentHashMap<String, Channel<JsonObject>>()

        @Volatile
        var closed = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject }
                .getOrNull() ?: return
            val id = message.string("id") ?: return
            waiting[id]?.trySend(message)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = shut(SessionClosed("cerrada ($code)"))

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            shut(SessionClosed(t.message ?: t.javaClass.simpleName))

        private fun shut(cause: SessionClosed) {
            closed = true
            opened.completeExceptionally(cause)
            waiting.values.forEach { it.close(cause) }
        }
    }

    private class SessionClosed(message: String) : IOException("conexión con la tele: $message")

    companion object {
        const val SEARCH_TARGET = "urn:lge-com:service:webos-second-screen:1"
        private const val PORT_TLS = 3001
        private const val PORT_PLAIN = 3000
        private const val CONNECT_TIMEOUT_MS = 4_000L
        private const val REQUEST_TIMEOUT_MS = 15_000L

        /** Long enough to find the remote and press OK on the television. */
        private const val PAIRING_TIMEOUT_MS = 60_000L

        fun upnpState(playState: String): String = when (playState.lowercase()) {
            "playing" -> "PLAYING"
            "paused" -> "PAUSED_PLAYBACK"
            "unloaded", "stopped" -> "STOPPED"
            else -> "TRANSITIONING"
        }

        private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

        /**
         * The registration every SSAP client sends: LG's own remote app's
         * manifest, signature included, which the television checks. Copied
         * verbatim from what the open-source clients (PyWebOSTV, lgtv2) send.
         */
        private val REGISTRATION: JsonObject by lazy {
            kotlinx.serialization.json.Json.parseToJsonElement(REGISTRATION_JSON).jsonObject
        }
    }
}

private const val SIGNATURE =
    "eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbmctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9." +
        "hrVRgjCwXVvE2OOSpDZ58hR+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRy" +
        "aMOv5zWSrthlf7G128qvIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4" +
        "RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1n" +
        "50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM" +
        "2DXzdKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAwaCViZNCP8iC9aO0q9fQoj" +
        "oa7NQnAtw=="

private val REGISTRATION_JSON = """
{
  "forcePairing": false,
  "pairingType": "PROMPT",
  "manifest": {
    "appVersion": "1.1",
    "manifestVersion": 1,
    "permissions": [
      "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "TEST_OPEN", "TEST_PROTECTED",
      "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING",
      "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV", "CONTROL_POWER", "READ_APP_STATUS",
      "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE", "READ_RUNNING_APPS",
      "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST", "READ_POWER_STATE", "READ_COUNTRY_INFO",
      "READ_SETTINGS", "CONTROL_TV_SCREEN", "CONTROL_TV_STANBY", "CONTROL_FAVORITE_GROUP",
      "CONTROL_USER_INFO", "CHECK_BLUETOOTH_DEVICE", "CONTROL_BLUETOOTH", "CONTROL_TIMER_INFO",
      "STB_INTERNAL_CONNECTION", "CONTROL_RECORDING", "READ_RECORDING_STATE", "WRITE_RECORDING_LIST",
      "READ_RECORDING_LIST", "READ_RECORDING_SCHEDULE", "WRITE_RECORDING_SCHEDULE",
      "READ_STORAGE_DEVICE_LIST", "READ_TV_PROGRAM_INFO", "CONTROL_BOX_CHANNEL",
      "READ_TV_ACR_AUTH_TOKEN", "READ_TV_CONTENT_STATE", "READ_TV_CURRENT_TIME",
      "ADD_LAUNCHER_CHANNEL", "SET_CHANNEL_SKIP", "RELEASE_CHANNEL_SKIP", "CONTROL_CHANNEL_BLOCK",
      "DELETE_SELECT_CHANNEL", "CONTROL_CHANNEL_GROUP", "SCAN_TV_CHANNELS", "CONTROL_TV_POWER",
      "CONTROL_WOL"
    ],
    "signatures": [{ "signature": "$SIGNATURE", "signatureVersion": 1 }],
    "signed": {
      "appId": "com.lge.test",
      "created": "20140509",
      "localizedAppNames": { "": "LG Remote App", "ko-KR": "리모컨 앱", "zxx-XX": "ЛГ Rэмotэ AПП" },
      "localizedVendorNames": { "": "LG Electronics" },
      "permissions": [
        "TEST_SECURE", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS",
        "READ_LGE_SDX", "READ_NOTIFICATIONS", "SEARCH", "WRITE_SETTINGS", "WRITE_NOTIFICATION_ALERT",
        "CONTROL_POWER", "READ_CURRENT_CHANNEL", "READ_RUNNING_APPS", "READ_UPDATE_INFO",
        "UPDATE_FROM_REMOTE_APP", "READ_LGE_TV_INPUT_EVENTS", "READ_TV_CURRENT_TIME"
      ],
      "serial": "2f930e2d2cfe083771f68e4fe7bb07",
      "vendorId": "com.lge"
    }
  }
}
"""
