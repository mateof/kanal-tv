package com.mateof.kanal.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * Everything in this database is a *cache* of what the provider serves. User
 * data (sources, favourites, history, settings) lives in DataStore, so wiping
 * the database on a schema change is harmless.
 */

@Entity(
    tableName = "categories",
    primaryKeys = ["sourceId", "kind", "categoryId"],
    indices = [Index("sourceId", "kind")]
)
data class CategoryEntity(
    val sourceId: String,
    /** LIVE / MOVIE / SERIES. */
    val kind: String,
    val categoryId: String,
    val name: String,
    val position: Int = 0,
    val itemCount: Int = 0
)

@Entity(
    tableName = "channels",
    primaryKeys = ["sourceId", "streamId"],
    indices = [
        Index("sourceId", "categoryId"),
        Index("sourceId", "sortName"),
        Index("epgChannelId")
    ]
)
data class ChannelEntity(
    val sourceId: String,
    /** Xtream stream id, or a stable hash of the url for M3U entries. */
    val streamId: String,
    val name: String,
    val sortName: String,
    val logo: String = "",
    val categoryId: String = "",
    val categoryName: String = "",
    val epgChannelId: String = "",
    val number: Int = 0,
    /** Direct url. Empty for Xtream, where it is built from the credentials. */
    val url: String = "",
    val archiveDays: Int = 0,
    val adult: Boolean = false,
    val position: Int = 0
)

@Entity(
    tableName = "movies",
    primaryKeys = ["sourceId", "streamId"],
    indices = [Index("sourceId", "categoryId"), Index("sourceId", "sortName")]
)
data class MovieEntity(
    val sourceId: String,
    val streamId: String,
    val name: String,
    val sortName: String,
    val cover: String = "",
    val categoryId: String = "",
    val categoryName: String = "",
    val containerExtension: String = "mp4",
    val rating: Double = 0.0,
    val addedAt: Long = 0L,
    val url: String = "",
    val adult: Boolean = false,
    val position: Int = 0
)

@Entity(
    tableName = "series",
    primaryKeys = ["sourceId", "seriesId"],
    indices = [Index("sourceId", "categoryId"), Index("sourceId", "sortName")]
)
data class SeriesEntity(
    val sourceId: String,
    val seriesId: String,
    val name: String,
    val sortName: String,
    val cover: String = "",
    val categoryId: String = "",
    val categoryName: String = "",
    val plot: String = "",
    val genre: String = "",
    val cast: String = "",
    val director: String = "",
    val releaseDate: String = "",
    val rating: Double = 0.0,
    val lastModified: Long = 0L,
    val adult: Boolean = false,
    val position: Int = 0
)

@Entity(
    tableName = "episodes",
    primaryKeys = ["sourceId", "episodeId"],
    indices = [Index("sourceId", "seriesId")]
)
data class EpisodeEntity(
    val sourceId: String,
    val episodeId: String,
    val seriesId: String,
    val season: Int = 1,
    val number: Int = 0,
    val title: String = "",
    val plot: String = "",
    val cover: String = "",
    val containerExtension: String = "mp4",
    val durationSecs: Int = 0,
    val airDate: String = "",
    /** Direct url, for M3U sources. Empty for Xtream. */
    val url: String = ""
)

/** Extra series metadata fetched lazily by the detail screen. */
@Entity(tableName = "series_details", primaryKeys = ["sourceId", "seriesId"])
data class SeriesDetailEntity(
    val sourceId: String,
    val seriesId: String,
    val backdrop: String = "",
    val fetchedAt: Long = 0L
)

@Entity(
    tableName = "epg",
    primaryKeys = ["sourceId", "channelId", "start"],
    indices = [Index("sourceId", "channelId", "start"), Index("stop")]
)
data class EpgEntity(
    val sourceId: String,
    /** XMLTV channel id, matched against [ChannelEntity.epgChannelId]. */
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String = "",
    val category: String = ""
)
