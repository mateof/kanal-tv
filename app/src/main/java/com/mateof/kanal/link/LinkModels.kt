package com.mateof.kanal.link

import com.mateof.kanal.data.model.Source
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What one Kanal tells another about itself: in answer to a broadcast, and on
 * `GET /kanal/v1/info` for a device added by address.
 */
@Serializable
data class PeerInfo(
    val id: String,
    val name: String,
    val manufacturer: String = "",
    val model: String = "",
    /** "tv", "tablet" or "phone". */
    val formFactor: String = "",
    val android: String = "",
    val app: String = "",
    /** Port the HTTP side listens on; the address is wherever the answer came from. */
    val port: Int = LinkPorts.HTTP,
    /** Title of what is on screen there, if anything. */
    val nowPlaying: String = ""
)

/** Another device with Kanal, as the lists show it. */
data class KanalPeer(val info: PeerInfo, val host: String) {
    val id: String get() = info.id
    val name: String get() = info.name
    val baseUrl: String get() = "http://${hostForUrl(host)}:${info.port}"

    private fun hostForUrl(host: String) = if (host.contains(':')) "[$host]" else host
}

/** Who is asking, so the other side can say so on screen. */
@Serializable
data class Sender(val id: String, val name: String)

/**
 * The account a stream belongs to, without its password: enough for the
 * receiver to tell whether it has the same source configured and can open the
 * item from its own catalogue instead of a bare url.
 */
@Serializable
data class SourceKey(val type: String, val url: String, val username: String)

@Serializable
data class PlayCommand(
    val from: Sender,
    /** LIVE, MOVIE or SERIES. */
    val kind: String,
    val itemId: String,
    val title: String,
    val subtitle: String = "",
    val logo: String = "",
    val isLive: Boolean = true,
    val seriesId: String = "",
    /** In order of preference; the receiver tries them as its own player would. */
    val urls: List<String>,
    val userAgent: String = "",
    /** Where the film was on the sender, to carry on from there. */
    val positionMs: Long = 0L,
    val source: SourceKey? = null
)

@Serializable
data class PlayAnswer(
    val ok: Boolean,
    /** "catalog" when opened from the receiver's own sources, "url" otherwise. */
    val mode: String = "",
    val error: String = ""
)

@Serializable
data class StopAnswer(val ok: Boolean, val positionMs: Long = 0L)

@Serializable
data class SourcesOffer(val from: Sender, val sources: List<Source>)

@Serializable
data class SourcesAnswer(
    val accepted: Boolean,
    val added: Int = 0,
    val updated: Int = 0
)

object LinkPorts {
    /** HTTP: info, play, stop and sources. */
    const val HTTP = 47820

    /** UDP: answers "who is there" broadcasts. */
    const val DISCOVERY = 47821
}

/** Tells a discovery packet from anything else that happens to reach the port. */
internal const val DISCOVERY_PREFIX = "KANAL_DISCOVER/1 "

internal val LinkJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
