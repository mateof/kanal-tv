package com.mateof.kanal.data.xtream

import com.mateof.kanal.data.model.Source
import java.net.URLEncoder

/**
 * URL building for Xtream Codes panels.
 *
 * Dispatcharr in particular is picky about the base url: users paste the whole
 * `http://host:port/player_api.php?username=...` string out of the panel, and
 * everything downstream breaks unless it is trimmed back to `http://host:port`.
 */
object XtreamUrls {

    /** Trims a pasted url down to scheme + host + port (+ any path prefix). */
    fun normalizeBase(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return url
        url = url.substringBefore('?').substringBefore('#')
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "http://$url"
        }
        for (suffix in KNOWN_ENDPOINTS) {
            val at = url.indexOf(suffix, ignoreCase = true)
            if (at >= 0) {
                url = url.substring(0, at)
                break
            }
        }
        return url.trimEnd('/')
    }

    fun playerApi(source: Source, action: String? = null, params: Map<String, String> = emptyMap()): String {
        val base = normalizeBase(source.url)
        val query = buildString {
            append("username=").append(enc(source.username))
            append("&password=").append(enc(source.password))
            if (action != null) append("&action=").append(enc(action))
            params.forEach { (key, value) ->
                append('&').append(enc(key)).append('=').append(enc(value))
            }
        }
        return "$base/player_api.php?$query"
    }

    fun live(source: Source, streamId: String, extension: String): String =
        "${normalizeBase(source.url)}/live/${encPath(source.username)}/${encPath(source.password)}/$streamId.$extension"

    /**
     * The pre-`/live/` form some panels (and a few Dispatcharr setups) still
     * serve, with no path prefix and no extension. Kept as a last resort when
     * both container variants fail.
     */
    fun legacyLive(source: Source, streamId: String): String =
        "${normalizeBase(source.url)}/${encPath(source.username)}/${encPath(source.password)}/$streamId"

    fun movie(source: Source, streamId: String, extension: String): String =
        "${normalizeBase(source.url)}/movie/${encPath(source.username)}/${encPath(source.password)}/$streamId.${extension.ifBlank { "mp4" }}"

    fun episode(source: Source, episodeId: String, extension: String): String =
        "${normalizeBase(source.url)}/series/${encPath(source.username)}/${encPath(source.password)}/$episodeId.${extension.ifBlank { "mp4" }}"

    /**
     * Catch-up / timeshift. [start] is `yyyy-MM-dd:HH-mm` in **UTC** and
     * [durationMinutes] the length of the programme.
     */
    fun timeshift(
        source: Source,
        streamId: String,
        durationMinutes: Int,
        start: String,
        extension: String
    ): String =
        "${normalizeBase(source.url)}/timeshift/${encPath(source.username)}/${encPath(source.password)}/" +
            "$durationMinutes/$start/$streamId.$extension"

    fun xmltv(source: Source): String {
        if (source.epgUrl.isNotBlank()) return source.epgUrl
        val base = normalizeBase(source.url)
        return "$base/xmltv.php?username=${enc(source.username)}&password=${enc(source.password)}"
    }

    fun m3u(source: Source): String {
        val base = normalizeBase(source.url)
        return "$base/get.php?username=${enc(source.username)}&password=${enc(source.password)}" +
            "&type=m3u_plus&output=ts"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Path segments keep `@` and friends readable but must not break the url. */
    private fun encPath(value: String): String = enc(value)

    private val KNOWN_ENDPOINTS = listOf(
        "/player_api.php", "/panel_api.php", "/get.php", "/xmltv.php", "/portal.php"
    )
}
