package com.mateof.kanal.data.xtream

import com.mateof.kanal.core.array
import com.mateof.kanal.core.asArrayOrEmpty
import com.mateof.kanal.core.asObject
import com.mateof.kanal.core.int
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.core.long
import com.mateof.kanal.core.str
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.net.HttpProvider
import com.mateof.kanal.data.net.redactUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** What `player_api.php` answers with no action: account + server details. */
data class XtreamAccount(
    val username: String,
    val status: String,
    val isActive: Boolean,
    val expiresAt: Long,
    val maxConnections: Int,
    val activeConnections: Int,
    val allowedFormats: List<String>,
    val serverUrl: String,
    val timezone: String
)

enum class XtreamCatalog(val categoriesAction: String, val itemsAction: String) {
    LIVE("get_live_categories", "get_live_streams"),
    VOD("get_vod_categories", "get_vod_streams"),
    SERIES("get_series_categories", "get_series")
}

class XtreamException(message: String, cause: Throwable? = null) : IOException(message, cause)

@Singleton
class XtreamClient @Inject constructor(
    private val http: HttpProvider,
    private val logger: FileLogger
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun authenticate(source: Source): XtreamAccount = withContext(Dispatchers.IO) {
        val root = fetch(source, XtreamUrls.playerApi(source)).asObject()
            ?: throw XtreamException("El servidor no devolvió una respuesta válida.")
        val user = root["user_info"].asObject()
            ?: throw XtreamException("El servidor no devolvió los datos de la cuenta. Revisa la URL.")

        val auth = user.int("auth", 1)
        val status = user.str("status", "Unknown")
        if (auth == 0) throw XtreamException("Usuario o contraseña incorrectos.")
        if (!status.equals("Active", ignoreCase = true) && status != "Unknown") {
            logger.w("Xtream", "La cuenta no está activa: $status")
        }
        val server = root["server_info"].asObject()

        XtreamAccount(
            username = user.str("username", source.username),
            status = status,
            isActive = status.equals("Active", ignoreCase = true) || status == "Unknown",
            expiresAt = user.long("exp_date") * 1000L,
            maxConnections = user.int("max_connections"),
            activeConnections = user.int("active_cons"),
            allowedFormats = user.array("allowed_output_formats")
                .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
            serverUrl = server?.str("url").orEmpty(),
            timezone = server?.str("timezone").orEmpty()
        )
    }

    suspend fun categories(source: Source, catalog: XtreamCatalog): List<JsonObject> =
        withContext(Dispatchers.IO) {
            fetch(source, XtreamUrls.playerApi(source, catalog.categoriesAction))
                .asArrayOrEmpty()
                .mapNotNull { it.asObject() }
        }

    /**
     * All items of a catalog. Asking without `category_id` is the fast path
     * (one request for the whole list); panels that reject it fall back to
     * walking the categories one by one.
     */
    suspend fun items(
        source: Source,
        catalog: XtreamCatalog,
        categoryIds: List<String>
    ): List<JsonObject> = withContext(Dispatchers.IO) {
        val bulk = runCatching {
            fetch(source, XtreamUrls.playerApi(source, catalog.itemsAction), http.longRunningClient)
                .asArrayOrEmpty()
                .mapNotNull { it.asObject() }
        }.getOrElse { error ->
            logger.w("Xtream", "${catalog.itemsAction} sin categoría falló, se irá por categorías", error)
            emptyList()
        }
        if (bulk.isNotEmpty() || categoryIds.isEmpty()) return@withContext bulk

        logger.i("Xtream", "${catalog.itemsAction}: recorriendo ${categoryIds.size} categorías")
        val all = ArrayList<JsonObject>()
        for (id in categoryIds) {
            val page = runCatching {
                fetch(
                    source,
                    XtreamUrls.playerApi(source, catalog.itemsAction, mapOf("category_id" to id)),
                    http.longRunningClient
                ).asArrayOrEmpty().mapNotNull { it.asObject() }
            }.getOrElse {
                logger.w("Xtream", "Categoría $id falló: ${it.message}")
                emptyList()
            }
            all += page
        }
        all
    }

    suspend fun seriesInfo(source: Source, seriesId: String): JsonObject? =
        withContext(Dispatchers.IO) {
            fetch(
                source,
                XtreamUrls.playerApi(source, "get_series_info", mapOf("series_id" to seriesId))
            ).asObject()
        }

    suspend fun vodInfo(source: Source, streamId: String): JsonObject? =
        withContext(Dispatchers.IO) {
            fetch(
                source,
                XtreamUrls.playerApi(source, "get_vod_info", mapOf("vod_id" to streamId))
            ).asObject()
        }

    /** Fallback guide for a single channel when there is no XMLTV to import. */
    suspend fun shortEpg(source: Source, streamId: String, limit: Int = 12): List<JsonObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = fetch(
                    source,
                    XtreamUrls.playerApi(
                        source,
                        "get_short_epg",
                        mapOf("stream_id" to streamId, "limit" to limit.toString())
                    )
                ).asObject()
                root?.get("epg_listings").asArrayOrEmpty().mapNotNull { it.asObject() }
            }.getOrElse {
                logger.w("Xtream", "get_short_epg falló para $streamId: ${it.message}")
                emptyList()
            }
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun fetch(
        source: Source,
        url: String,
        client: OkHttpClient = http.client
    ): JsonElement {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", source.userAgent.ifBlank { "Kanal/1.0" })
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw XtreamException(
                    when (response.code) {
                        401, 403 -> "Acceso denegado (${response.code}). Revisa usuario y contraseña."
                        404 -> "El servidor no tiene player_api.php en ${redactUrl(url)}."
                        512, 521, 522 -> "El servidor no responde (${response.code})."
                        else -> "El servidor respondió ${response.code}."
                    }
                )
            }
            val body = response.body ?: throw XtreamException("Respuesta vacía del servidor.")
            return try {
                json.decodeFromStream<JsonElement>(body.byteStream())
            } catch (e: Exception) {
                throw XtreamException(
                    "La respuesta no es JSON válido. ¿Seguro que la URL es la del panel Xtream?",
                    e
                )
            }
        }
    }
}
