package com.mateof.kanal.data.repo

import com.mateof.kanal.data.db.ChannelEntity
import com.mateof.kanal.data.db.EpgEntity
import com.mateof.kanal.data.db.EpisodeEntity
import com.mateof.kanal.data.db.MovieEntity
import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.data.model.HistoryItem
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.model.SourceType
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.prefs.DEFAULT_USER_AGENT
import com.mateof.kanal.data.prefs.StreamFormat
import com.mateof.kanal.data.xtream.XtreamUrls
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the player screen needs to start, in one object. */
data class Playable(
    val kind: ContentKind,
    val sourceId: String,
    val itemId: String,
    val title: String,
    val subtitle: String,
    val logo: String,
    val url: String,
    val userAgent: String,
    val isLive: Boolean,
    val seriesId: String = "",
    val startPositionMs: Long = 0L,
    /** Xtream channel id used to look the guide up while playing. */
    val epgChannelId: String = "",
    /**
     * Other urls for the same content, tried in order when the first one fails
     * to parse. Panels are not consistent: within one account some channels
     * only answer in MPEG-TS, others only in HLS, and a few still use the old
     * prefix-less path.
     */
    val fallbackUrls: List<String> = emptyList()
)

@Singleton
class PlaybackRepository @Inject constructor(
    private val prefs: AppPreferences
) {
    suspend fun forChannel(source: Source, channel: ChannelEntity): Playable {
        val settings = prefs.settings.first()
        val url: String
        val fallbacks: List<String>
        when (source.type) {
            SourceType.M3U -> {
                url = channel.url
                fallbacks = emptyList()
            }

            SourceType.XTREAM -> {
                val preferred = settings.streamFormat
                val other = if (preferred == StreamFormat.TS) StreamFormat.HLS else StreamFormat.TS
                url = XtreamUrls.live(source, channel.streamId, preferred.extension)
                fallbacks = listOf(
                    XtreamUrls.live(source, channel.streamId, other.extension),
                    XtreamUrls.legacyLive(source, channel.streamId)
                )
            }
        }
        return Playable(
            kind = ContentKind.LIVE,
            sourceId = source.id,
            itemId = channel.streamId,
            title = channel.name,
            subtitle = channel.categoryName,
            logo = channel.logo,
            url = url,
            userAgent = userAgentFor(source),
            isLive = true,
            epgChannelId = channel.epgChannelId,
            fallbackUrls = fallbacks
        )
    }

    suspend fun forMovie(source: Source, movie: MovieEntity): Playable {
        val url = when (source.type) {
            SourceType.M3U -> movie.url
            SourceType.XTREAM -> XtreamUrls.movie(source, movie.streamId, movie.containerExtension)
        }
        return Playable(
            kind = ContentKind.MOVIE,
            sourceId = source.id,
            itemId = movie.streamId,
            title = movie.name,
            subtitle = movie.categoryName,
            logo = movie.cover,
            url = url,
            userAgent = userAgentFor(source),
            isLive = false,
            startPositionMs = prefs.resumePositionOf("${ContentKind.MOVIE.name}:${source.id}:${movie.streamId}")
        )
    }

    suspend fun forEpisode(source: Source, episode: EpisodeEntity, seriesName: String): Playable {
        val url = when (source.type) {
            SourceType.M3U -> episode.url
            SourceType.XTREAM -> XtreamUrls.episode(source, episode.episodeId, episode.containerExtension)
        }
        return Playable(
            kind = ContentKind.SERIES,
            sourceId = source.id,
            itemId = episode.episodeId,
            title = "${episode.season}x${episode.number.toString().padStart(2, '0')} · ${episode.title}",
            subtitle = seriesName,
            logo = episode.cover,
            url = url,
            userAgent = userAgentFor(source),
            isLive = false,
            seriesId = episode.seriesId,
            startPositionMs = prefs.resumePositionOf("${ContentKind.SERIES.name}:${source.id}:${episode.episodeId}")
        )
    }

    /**
     * Catch-up playback of an already-aired programme. Only Xtream panels with
     * `tv_archive` expose it, and the timestamp must be UTC.
     */
    suspend fun forCatchup(source: Source, channel: ChannelEntity, programme: EpgEntity): Playable? {
        if (source.type != SourceType.XTREAM || channel.archiveDays <= 0) return null
        val settings = prefs.settings.first()
        val minutes = ((programme.stop - programme.start) / 60_000L).toInt().coerceAtLeast(1)
        val start = TIMESHIFT_FORMAT.get()!!.format(Date(programme.start))
        return Playable(
            kind = ContentKind.LIVE,
            sourceId = source.id,
            itemId = "${channel.streamId}@${programme.start}",
            title = programme.title,
            subtitle = "${channel.name} · Repetición",
            logo = channel.logo,
            url = XtreamUrls.timeshift(
                source, channel.streamId, minutes, start, settings.streamFormat.extension
            ),
            userAgent = userAgentFor(source),
            isLive = false,
            epgChannelId = channel.epgChannelId
        )
    }

    suspend fun record(playable: Playable, positionMs: Long, durationMs: Long) {
        // Live has no meaningful position, but it still belongs in "recientes".
        prefs.recordPlayback(
            HistoryItem(
                sourceId = playable.sourceId,
                kind = playable.kind,
                itemId = playable.itemId,
                name = playable.title,
                logo = playable.logo,
                seriesId = playable.seriesId,
                positionMs = if (playable.isLive) 0L else positionMs,
                durationMs = if (playable.isLive) 0L else durationMs,
                playedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun userAgentFor(source: Source): String =
        source.userAgent.ifBlank { prefs.settings.first().userAgent }.ifBlank { DEFAULT_USER_AGENT }

    private companion object {
        val TIMESHIFT_FORMAT: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
    }
}
