package com.mateof.kanal.data.dispatcharr

import com.mateof.kanal.core.asArrayOrEmpty
import com.mateof.kanal.core.asObject
import com.mateof.kanal.core.double
import com.mateof.kanal.core.int
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.core.str
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.net.HttpProvider
import com.mateof.kanal.data.xtream.XtreamUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** One channel the server is serving right now, to whoever is watching it. */
data class WatchedChannel(
    /** Dispatcharr's own channel uuid, its key back into the panel's catalogue. */
    val uuid: String,
    val name: String,
    /** Devices pulling this channel. Several share a single provider connection. */
    val clients: Int,
    /** When the channel went up, in wall-clock millis. */
    val startedAt: Long,
    val resolution: String
)

class DispatcharrException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The part of the Dispatcharr API that says what is on the air.
 *
 * Nothing here is required to watch television: without an API key the client
 * is never called, and any panel that is not Dispatcharr simply has no such
 * endpoint. It is read-only — this app never asks the server to stop a client.
 */
@Singleton
class DispatcharrClient @Inject constructor(
    private val http: HttpProvider,
    private val logger: FileLogger
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Live channels being served. VOD and catch-up come in the same response
     * and are ignored: a film someone else is watching is at their own minute,
     * so offering to join it would only mislead.
     */
    suspend fun watching(source: Source): List<WatchedChannel> = withContext(Dispatchers.IO) {
        val root = fetch(source, "${base(source)}/proxy/stats/").asObject()
            ?: throw DispatcharrException("El servidor no devolvió estadísticas.")
        root["live"].asObject()?.get("channels").asArrayOrEmpty()
            .mapNotNull { it.asObject() }
            .filter { it.str("state", "active").equals("active", ignoreCase = true) }
            .map { channel ->
                WatchedChannel(
                    uuid = channel.str("channel_id"),
                    name = channel.str("channel_name").ifBlank { channel.str("stream_name") },
                    // Clients are listed one by one; the count is a shortcut
                    // that older versions do not always fill in.
                    clients = channel.int(
                        "client_count",
                        channel["clients"].asArrayOrEmpty().size
                    ),
                    startedAt = (channel.double("started_at") * 1000).toLong(),
                    resolution = channel.str("resolution")
                )
            }
            .filter { it.name.isNotBlank() }
    }

    /**
     * The panel's own uuid → id map, which is what an Xtream catalogue stores
     * as its stream id. Only asked for when a uuid cannot be resolved from what
     * is already on the device, because it answers with the whole catalogue.
     */
    suspend fun channelIdsByUuid(source: Source): Map<String, String> = withContext(Dispatchers.IO) {
        fetch(source, "${base(source)}/api/channels/channels/summary/")
            .asArrayOrEmpty()
            .mapNotNull { it.asObject() }
            .mapNotNull { channel ->
                val uuid = channel.str("uuid")
                val id = channel.str("id")
                if (uuid.isBlank() || id.isBlank()) null else uuid to id
            }
            .toMap()
    }

    /**
     * Where the panel lives. An Xtream source already points at it; an M3U one
     * points at a playlist somewhere under it, so it is cut back to the origin.
     */
    private fun base(source: Source): String =
        if (source.isXtream) {
            XtreamUrls.normalizeBase(source.url)
        } else {
            source.url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }.orEmpty()
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun fetch(source: Source, url: String): JsonElement {
        if (source.apiKey.isBlank()) throw DispatcharrException("Falta la clave de API.")
        val request = Request.Builder()
            .url(url)
            // The key never travels in the url: it would land in the log file,
            // which is meant to be shareable, and redaction only covers queries.
            .header("X-API-Key", source.apiKey)
            .header("Accept", "application/json")
            .build()
        http.client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw DispatcharrException(
                    "El servidor rechazó la clave de API (${response.code})."
                )
            }
            if (!response.isSuccessful) {
                throw DispatcharrException("El servidor respondió ${response.code}.")
            }
            val body = response.body ?: throw DispatcharrException("Respuesta vacía.")
            return runCatching { json.decodeFromStream<JsonElement>(body.byteStream()) }
                .getOrElse { error ->
                    logger.w("Dispatcharr", "Respuesta ilegible de $url", error)
                    throw DispatcharrException("La respuesta no era JSON.", error)
                }
        }
    }
}
