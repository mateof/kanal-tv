package com.mateof.kanal.data.model

import kotlinx.serialization.Serializable

enum class SourceType { XTREAM, M3U }

/**
 * A configured provider. Xtream sources talk to `player_api.php`; M3U sources
 * download a playlist (and optionally an XMLTV guide) over plain HTTP.
 */
@Serializable
data class Source(
    val id: String,
    val name: String,
    val type: SourceType,
    /** Xtream: server base url. M3U: full playlist url. */
    val url: String,
    val username: String = "",
    val password: String = "",
    /** XMLTV url. Empty means "derive it" (xmltv.php for Xtream, url-tvg for M3U). */
    val epgUrl: String = "",
    val userAgent: String = "",
    val createdAt: Long = 0L,
    val lastSyncAt: Long = 0L,
    val lastEpgSyncAt: Long = 0L
) {
    val isXtream: Boolean get() = type == SourceType.XTREAM
}

enum class ContentKind { LIVE, MOVIE, SERIES }

/** Entry of the "continuar viendo" list. Kept small and stored in DataStore. */
@Serializable
data class HistoryItem(
    val sourceId: String,
    val kind: ContentKind,
    /** Stream id for live/movies, episode id for series. */
    val itemId: String,
    val name: String,
    val logo: String = "",
    /** Series only, so the detail screen can be reopened. */
    val seriesId: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playedAt: Long = 0L
) {
    val key: String get() = "${kind.name}:$sourceId:$itemId"

    /** Anything past 95% counts as finished and stops being offered to resume. */
    val isFinished: Boolean
        get() = durationMs > 0 && positionMs > durationMs * 0.95

    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** Stable key for the favourites set. */
fun favoriteKey(kind: ContentKind, sourceId: String, itemId: String): String =
    "${kind.name}:$sourceId:$itemId"
