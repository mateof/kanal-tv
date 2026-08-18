package com.mateof.kanal.data.logos

import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.net.HttpProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Where the logos live, listed a country at a time. */
private const val LISTING =
    "https://api.github.com/repos/tv-logo/tv-logos/contents/countries/%s"

/** Bits of a channel name that describe the feed, not the channel. */
private val QUALITY = Regex(
    "\\b(hd|fhd|uhd|sd|4k|8k|hevc|h265|h264|1080p?|720p?|540p?|480p?|multi|raw|backup|alt)\\b"
)

/** A country tag some providers put in front of every name: "ES|", "[ES]", "ES:". */
private val COUNTRY_TAG = Regex("^\\s*[\\[(]?[a-z]{2,3}[\\])]?\\s*[|:\\-]\\s*")

/**
 * Fills in the logos a playlist does not carry.
 *
 * Plenty of lists ship no `tvg-logo` at all — the whole channel list then reads
 * as identical grey tiles — while the channels themselves are perfectly well
 * known ones. This matches their names against a public catalogue of station
 * logos, one country's folder at a time: a few hundred entries rather than the
 * forty thousand a worldwide index would cost to download and sift on a stick.
 *
 * Nothing about the user's list is sent anywhere: this is a plain read of a
 * public folder listing, and the matching happens here.
 */
@Singleton
class LogoCatalog @Inject constructor(
    private val http: HttpProvider,
    private val logger: FileLogger
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Slug to logo url, per country folder, for as long as the app is up. */
    private val cached = mutableMapOf<String, Map<String, String>>()

    /**
     * @param country an ISO country code; the catalogue is filed under the
     *   English name of the country, which is what [Locale] can produce.
     */
    suspend fun forCountry(country: String): Map<String, String> {
        val folder = folderFor(country) ?: return emptyMap()
        cached[folder]?.let { return it }

        val listing = withContext(Dispatchers.IO) { download(folder) } ?: return emptyMap()
        cached[folder] = listing
        logger.i("Logos", "Catálogo de '$folder': ${listing.size} logotipos")
        return listing
    }

    /** @return the best logo for [name], or null when nothing looks like it. */
    fun match(catalogue: Map<String, String>, name: String): String? {
        if (catalogue.isEmpty()) return null
        val bare = slug(name)
        catalogue[bare]?.let { return it }
        // Same channel, different feed: "La 1 HD" is filed as plain "la-1".
        val withoutQuality = slug(name, dropQuality = true)
        return catalogue[withoutQuality]
    }

    private fun download(folder: String): Map<String, String>? = runCatching {
        val request = Request.Builder()
            .url(LISTING.format(folder))
            .header("Accept", "application/vnd.github+json")
            .build()
        http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logger.w("Logos", "El catálogo de '$folder' respondió ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            val entries = json.parseToJsonElement(body) as? JsonArray ?: return null
            entries.mapNotNull { entry ->
                val item = entry.jsonObject
                val fileName = item["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val url = item["download_url"]?.jsonPrimitive?.contentOrNullSafe()
                    ?: return@mapNotNull null
                if (!fileName.endsWith(".png", ignoreCase = true)) return@mapNotNull null
                fileSlug(fileName) to url
            }.toMap()
        }
    }.getOrElse { failure ->
        logger.w("Logos", "No se pudo leer el catálogo de '$folder'", failure)
        null
    }

    /** `antena-3-hd-es.png` is filed under `antena-3-hd`. */
    private fun fileSlug(fileName: String): String =
        fileName.removeSuffix(".png").removeSuffix(".PNG")
            .replace(Regex("-[a-z]{2}$"), "")

    private fun folderFor(country: String): String? {
        val name = Locale("", country).getDisplayCountry(Locale.ENGLISH)
        if (name.isBlank() || name.equals(country, ignoreCase = true)) return null
        return name.lowercase(Locale.ENGLISH).replace(' ', '-')
    }

    private fun slug(raw: String, dropQuality: Boolean = false): String {
        var text = raw.lowercase(Locale.ROOT).replace(COUNTRY_TAG, "")
        text = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        if (dropQuality) text = text.replace(QUALITY, " ")
        return text.replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }
}

/** `download_url` is null for folders, and JsonNull is not a string. */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
