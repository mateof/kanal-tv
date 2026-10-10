package com.mateof.kanal.cast

import java.io.IOException

/**
 * What a renderer is handed: the address it will fetch and how that resource
 * is described to it.
 *
 * The description has to agree with what is actually served. Picky renderers
 * (LG and Samsung among them) compare the protocolInfo in the metadata with the
 * Content-Type and contentFeatures they get back, and refuse a mismatch.
 */
data class CastSource(
    val url: String,
    val mime: String,
    /** Fourth field of protocolInfo; the relay also sends it as contentFeatures.dlna.org. */
    val features: String,
    /** How it got here, for the log: [ROUTE_DIRECT], [ROUTE_RELAY] or [ROUTE_FALLBACK]. */
    val route: String
) {
    companion object {
        const val ROUTE_DIRECT = "directo"
        const val ROUTE_RELAY = "relay"
        const val ROUTE_FALLBACK = "fallback→relay"

        /** Exactly what was handed over before the relay existed. */
        fun direct(url: String): CastSource =
            CastSource(url, Dlna.mimeOf(url), Dlna.LEGACY_FEATURES, ROUTE_DIRECT)
    }
}

object Dlna {
    /**
     * Live stream, no seeking. What the direct route has always announced, for
     * films too, and it stays that way so the direct route does not change.
     */
    const val LEGACY_FEATURES = "DLNA.ORG_OP=00;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    /**
     * Live through the relay: no time or byte seeking (OP=00), not transcoded
     * (CI=0), streaming transfer mode, background mode, connection stall and
     * DLNA 1.5 (FLAGS=0170…).
     */
    const val LIVE_FEATURES = "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    /** A file through the relay: byte-range seeking (OP=01), which the relay forwards. */
    const val SEEKABLE_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    const val MIME_TS = "video/mp2t"
    const val MIME_HLS = "application/x-mpegURL"

    /** Renderers match on this; announcing the wrong container gets refused. */
    fun mimeOf(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> MIME_HLS
            path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".ts") -> MIME_TS
            else -> "video/mpeg"
        }
    }

    /** The extension the relay puts on its url, for renderers that judge by it. */
    fun extensionOf(mime: String): String = when (mime) {
        MIME_TS -> ".ts"
        "video/mp4" -> ".mp4"
        "video/x-matroska" -> ".mkv"
        else -> ""
    }

    fun protocolInfo(mime: String, features: String): String = "http-get:*:$mime:$features"
}

/**
 * A renderer's refusal, kept typed so the decision to retry through the relay
 * can look at the code instead of parsing a sentence.
 *
 * The message is the same one the sheets have always shown verbatim.
 */
class UpnpException(
    val action: String,
    val httpCode: Int,
    val upnpCode: Int?,
    val description: String?
) : IOException(
    buildString {
        append("$action devolvió $httpCode")
        if (upnpCode != null) append(" · UPnP $upnpCode")
        if (description != null) append(" · $description")
    }
)
