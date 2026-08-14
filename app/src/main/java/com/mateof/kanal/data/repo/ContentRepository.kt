package com.mateof.kanal.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.annotation.StringRes
import com.mateof.kanal.R
import com.mateof.kanal.core.normalizedForSearch
import com.mateof.kanal.data.db.CategoryEntity
import com.mateof.kanal.data.db.ChannelEntity
import com.mateof.kanal.data.db.EpisodeEntity
import com.mateof.kanal.data.db.KanalDatabase
import com.mateof.kanal.data.db.MovieEntity
import com.mateof.kanal.data.db.SeriesEntity
import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.data.prefs.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class CatalogSort(val key: String, @StringRes val labelRes: Int) {
    DEFAULT("default", R.string.sort_default),
    NAME("name", R.string.sort_name),
    RECENT("recent", R.string.sort_recent),
    RATING("rating", R.string.sort_rating)
}

data class SearchResults(
    val channels: List<ChannelEntity> = emptyList(),
    val movies: List<MovieEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList()
) {
    val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
    val total: Int get() = channels.size + movies.size + series.size
}

@Singleton
class ContentRepository @Inject constructor(
    private val db: KanalDatabase,
    private val prefs: AppPreferences
) {
    private fun pagingConfig() = PagingConfig(
        pageSize = 60,
        prefetchDistance = 60,
        initialLoadSize = 120,
        enablePlaceholders = false
    )

    private val includeAdult: Flow<Boolean> = prefs.settings.map { !it.hideAdult }

    fun categories(sourceId: String, kind: ContentKind): Flow<List<CategoryEntity>> =
        db.categories().observe(sourceId, kind.name.toDbKind())

    fun channels(sourceId: String, categoryId: String, query: String): Flow<PagingData<ChannelEntity>> =
        includeAdult.flatMapLatest { adult ->
            Pager(pagingConfig()) {
                db.channels().paged(sourceId, categoryId, query.normalizedForSearch(), adult)
            }.flow
        }

    fun movies(
        sourceId: String,
        categoryId: String,
        query: String,
        sort: CatalogSort
    ): Flow<PagingData<MovieEntity>> = includeAdult.flatMapLatest { adult ->
        Pager(pagingConfig()) {
            db.movies().paged(sourceId, categoryId, query.normalizedForSearch(), adult, sort.key)
        }.flow
    }

    fun series(
        sourceId: String,
        categoryId: String,
        query: String,
        sort: CatalogSort
    ): Flow<PagingData<SeriesEntity>> = includeAdult.flatMapLatest { adult ->
        Pager(pagingConfig()) {
            db.series().paged(sourceId, categoryId, query.normalizedForSearch(), adult, sort.key)
        }.flow
    }

    /** Ordered ids of the current channel selection, used to zap up/down. */
    suspend fun channelIds(sourceId: String, categoryId: String, query: String): List<String> =
        db.channels().idsFor(sourceId, categoryId, query.normalizedForSearch(), !prefs.settings.first().hideAdult)

    suspend fun channel(sourceId: String, streamId: String): ChannelEntity? =
        db.channels().byId(sourceId, streamId)

    /**
     * Whole rows for the given ids, in no particular order.
     *
     * The player's strip of logos asks for a window around whatever is selected
     * rather than the whole selection: a playlist of forty thousand channels
     * would otherwise be held in memory in full for the sake of the eight tiles
     * actually on screen.
     */
    suspend fun channelsByIds(sourceId: String, ids: List<String>): List<ChannelEntity> =
        if (ids.isEmpty()) emptyList() else db.channels().rowsByIds(sourceId, ids)

    /** Whole rows for the guide wall, capped so a 40k playlist cannot drown it. */
    suspend fun channelList(
        sourceId: String,
        categoryId: String,
        limit: Int = 150
    ): List<ChannelEntity> =
        db.channels().listFor(sourceId, categoryId, !prefs.settings.first().hideAdult, limit)

    suspend fun movie(sourceId: String, streamId: String): MovieEntity? =
        db.movies().byId(sourceId, streamId)

    suspend fun seriesById(sourceId: String, seriesId: String): SeriesEntity? =
        db.series().byId(sourceId, seriesId)

    suspend fun episode(sourceId: String, episodeId: String): EpisodeEntity? =
        db.episodes().byId(sourceId, episodeId)

    fun episodes(sourceId: String, seriesId: String): Flow<List<EpisodeEntity>> =
        db.episodes().observe(sourceId, seriesId)

    fun recentMovies(sourceId: String, limit: Int = 24): Flow<List<MovieEntity>> =
        includeAdult.flatMapLatest { db.movies().recent(sourceId, it, limit) }

    fun recentSeries(sourceId: String, limit: Int = 24): Flow<List<SeriesEntity>> =
        includeAdult.flatMapLatest { db.series().recent(sourceId, it, limit) }

    fun counts(sourceId: String): Flow<Triple<Int, Int, Int>> = combine(
        db.channels().count(sourceId),
        db.movies().count(sourceId),
        db.series().count(sourceId)
    ) { channels, movies, series -> Triple(channels, movies, series) }

    suspend fun search(sourceId: String, rawQuery: String, limit: Int = 60): SearchResults {
        val query = rawQuery.trim().normalizedForSearch()
        if (query.length < 2) return SearchResults()
        val adult = !prefs.settings.first().hideAdult
        return SearchResults(
            channels = db.channels().search(sourceId, query, adult, limit),
            movies = db.movies().search(sourceId, query, adult, limit),
            series = db.series().search(sourceId, query, adult, limit)
        )
    }

    // --- Favourites ----------------------------------------------------------

    fun favoriteChannels(sourceId: String): Flow<List<ChannelEntity>> =
        favoriteIds(sourceId, ContentKind.LIVE).flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList()) else db.channels().observeByIds(sourceId, ids)
        }

    fun favoriteMovies(sourceId: String): Flow<List<MovieEntity>> =
        favoriteIds(sourceId, ContentKind.MOVIE).flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList()) else db.movies().observeByIds(sourceId, ids)
        }

    fun favoriteSeries(sourceId: String): Flow<List<SeriesEntity>> =
        favoriteIds(sourceId, ContentKind.SERIES).flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList()) else db.series().observeByIds(sourceId, ids)
        }

    private fun favoriteIds(sourceId: String, kind: ContentKind): Flow<List<String>> =
        prefs.favorites.map { keys ->
            val prefix = "${kind.name}:$sourceId:"
            keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        }
}

/** [ContentKind] maps 1:1 to the `kind` column, but the names must not drift. */
private fun String.toDbKind(): String = when (this) {
    ContentKind.LIVE.name -> "LIVE"
    ContentKind.MOVIE.name -> "MOVIE"
    else -> "SERIES"
}
