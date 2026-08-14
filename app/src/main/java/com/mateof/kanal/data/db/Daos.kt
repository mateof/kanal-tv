package com.mateof.kanal.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE sourceId = :sourceId AND kind = :kind ORDER BY position")
    fun observe(sourceId: String, kind: String): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE sourceId = :sourceId AND kind = :kind")
    suspend fun clear(sourceId: String, kind: String)

    @Transaction
    suspend fun replace(sourceId: String, kind: String, items: List<CategoryEntity>) {
        clear(sourceId, kind)
        insertAll(items)
    }
}

@Dao
interface ChannelDao {
    @Query(
        """
        SELECT * FROM channels
        WHERE sourceId = :sourceId
          AND (:categoryId = '' OR categoryId = :categoryId)
          AND (:query = '' OR sortName LIKE '%' || :query || '%')
          AND (:includeAdult = 1 OR adult = 0)
        ORDER BY position
        """
    )
    fun paged(
        sourceId: String,
        categoryId: String,
        query: String,
        includeAdult: Boolean
    ): PagingSource<Int, ChannelEntity>

    @Query(
        """
        SELECT streamId FROM channels
        WHERE sourceId = :sourceId
          AND (:categoryId = '' OR categoryId = :categoryId)
          AND (:query = '' OR sortName LIKE '%' || :query || '%')
          AND (:includeAdult = 1 OR adult = 0)
        ORDER BY position
        """
    )
    suspend fun idsFor(
        sourceId: String,
        categoryId: String,
        query: String,
        includeAdult: Boolean
    ): List<String>

    @Query(
        """
        SELECT * FROM channels
        WHERE sourceId = :sourceId AND sortName LIKE '%' || :query || '%'
          AND (:includeAdult = 1 OR adult = 0)
        ORDER BY position LIMIT :limit
        """
    )
    suspend fun search(sourceId: String, query: String, includeAdult: Boolean, limit: Int): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId AND streamId = :streamId")
    suspend fun byId(sourceId: String, streamId: String): ChannelEntity?

    /** Whole rows for a handful of ids, for the strip of logos in the player. */
    @Query("SELECT * FROM channels WHERE sourceId = :sourceId AND streamId IN (:ids)")
    suspend fun rowsByIds(sourceId: String, ids: List<String>): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId AND streamId IN (:ids)")
    fun observeByIds(sourceId: String, ids: List<String>): Flow<List<ChannelEntity>>

    @Query("SELECT COUNT(*) FROM channels WHERE sourceId = :sourceId")
    fun count(sourceId: String): Flow<Int>

    /** Plain list for the guide wall, which needs whole rows rather than pages. */
    @Query(
        """
        SELECT * FROM channels
        WHERE sourceId = :sourceId
          AND (:categoryId = '' OR categoryId = :categoryId)
          AND (:includeAdult = 1 OR adult = 0)
        ORDER BY position LIMIT :limit
        """
    )
    suspend fun listFor(
        sourceId: String,
        categoryId: String,
        includeAdult: Boolean,
        limit: Int
    ): List<ChannelEntity>

    /** Channels the playlist gave no `tvg-id` for, to be matched by name. */
    @Query("SELECT * FROM channels WHERE sourceId = :sourceId AND epgChannelId = ''")
    suspend fun withoutEpgId(sourceId: String): List<ChannelEntity>

    @Query("SELECT DISTINCT epgChannelId FROM channels WHERE sourceId = :sourceId AND epgChannelId != ''")
    suspend fun epgIds(sourceId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE sourceId = :sourceId")
    suspend fun clear(sourceId: String)
}

@Dao
interface MovieDao {
    @Query(
        """
        SELECT * FROM movies
        WHERE sourceId = :sourceId
          AND (:categoryId = '' OR categoryId = :categoryId)
          AND (:query = '' OR sortName LIKE '%' || :query || '%')
          AND (:includeAdult = 1 OR adult = 0)
        ORDER BY
          CASE WHEN :sort = 'recent' THEN addedAt END DESC,
          CASE WHEN :sort = 'rating' THEN rating END DESC,
          CASE WHEN :sort = 'name' THEN sortName END ASC,
          position
        """
    )
    fun paged(
        sourceId: String,
        categoryId: String,
        query: String,
        includeAdult: Boolean,
        sort: String
    ): PagingSource<Int, MovieEntity>

    @Query(
        """
        SELECT * FROM movies
        WHERE sourceId = :sourceId AND sortName LIKE '%' || :query || '%'
          AND (:includeAdult = 1 OR adult = 0)
        ORDER BY sortName LIMIT :limit
        """
    )
    suspend fun search(sourceId: String, query: String, includeAdult: Boolean, limit: Int): List<MovieEntity>

    @Query(
        """
        SELECT * FROM movies WHERE sourceId = :sourceId AND (:includeAdult = 1 OR adult = 0)
        ORDER BY addedAt DESC LIMIT :limit
        """
    )
    fun recent(sourceId: String, includeAdult: Boolean, limit: Int): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE sourceId = :sourceId AND streamId = :streamId")
    suspend fun byId(sourceId: String, streamId: String): MovieEntity?

    @Query("SELECT * FROM movies WHERE sourceId = :sourceId AND streamId IN (:ids)")
    fun observeByIds(sourceId: String, ids: List<String>): Flow<List<MovieEntity>>

    @Query("SELECT COUNT(*) FROM movies WHERE sourceId = :sourceId")
    fun count(sourceId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MovieEntity>)

    @Query("DELETE FROM movies WHERE sourceId = :sourceId")
    suspend fun clear(sourceId: String)
}

@Dao
interface SeriesDao {
    @Query(
        """
        SELECT * FROM series
        WHERE sourceId = :sourceId
          AND (:categoryId = '' OR categoryId = :categoryId)
          AND (:query = '' OR sortName LIKE '%' || :query || '%')
          AND (:includeAdult = 1 OR adult = 0)
        ORDER BY
          CASE WHEN :sort = 'recent' THEN lastModified END DESC,
          CASE WHEN :sort = 'rating' THEN rating END DESC,
          CASE WHEN :sort = 'name' THEN sortName END ASC,
          position
        """
    )
    fun paged(
        sourceId: String,
        categoryId: String,
        query: String,
        includeAdult: Boolean,
        sort: String
    ): PagingSource<Int, SeriesEntity>

    @Query(
        """
        SELECT * FROM series
        WHERE sourceId = :sourceId AND sortName LIKE '%' || :query || '%'
          AND (:includeAdult = 1 OR adult = 0)
        ORDER BY sortName LIMIT :limit
        """
    )
    suspend fun search(sourceId: String, query: String, includeAdult: Boolean, limit: Int): List<SeriesEntity>

    @Query(
        """
        SELECT * FROM series WHERE sourceId = :sourceId AND (:includeAdult = 1 OR adult = 0)
        ORDER BY lastModified DESC LIMIT :limit
        """
    )
    fun recent(sourceId: String, includeAdult: Boolean, limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE sourceId = :sourceId AND seriesId = :seriesId")
    suspend fun byId(sourceId: String, seriesId: String): SeriesEntity?

    @Query("SELECT * FROM series WHERE sourceId = :sourceId AND seriesId IN (:ids)")
    fun observeByIds(sourceId: String, ids: List<String>): Flow<List<SeriesEntity>>

    @Query("SELECT COUNT(*) FROM series WHERE sourceId = :sourceId")
    fun count(sourceId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SeriesEntity>)

    @Query("DELETE FROM series WHERE sourceId = :sourceId")
    suspend fun clear(sourceId: String)
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE sourceId = :sourceId AND seriesId = :seriesId ORDER BY season, number")
    fun observe(sourceId: String, seriesId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE sourceId = :sourceId AND episodeId = :episodeId")
    suspend fun byId(sourceId: String, episodeId: String): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<EpisodeEntity>)

    @Query("DELETE FROM episodes WHERE sourceId = :sourceId AND seriesId = :seriesId")
    suspend fun clearSeries(sourceId: String, seriesId: String)

    @Query("DELETE FROM episodes WHERE sourceId = :sourceId")
    suspend fun clear(sourceId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDetail(detail: SeriesDetailEntity)

    @Query("SELECT * FROM series_details WHERE sourceId = :sourceId AND seriesId = :seriesId")
    suspend fun detail(sourceId: String, seriesId: String): SeriesDetailEntity?

    @Transaction
    suspend fun replaceSeries(sourceId: String, seriesId: String, items: List<EpisodeEntity>) {
        clearSeries(sourceId, seriesId)
        insertAll(items)
    }
}

@Dao
interface EpgDao {
    /** One row per channel: whatever is on air at [at]. */
    @Query("SELECT * FROM epg WHERE sourceId = :sourceId AND start <= :at AND stop > :at")
    fun nowPlaying(sourceId: String, at: Long): Flow<List<EpgEntity>>

    @Query(
        """
        SELECT * FROM epg WHERE sourceId = :sourceId AND channelId = :channelId
          AND stop > :from AND start < :to ORDER BY start
        """
    )
    suspend fun window(sourceId: String, channelId: String, from: Long, to: Long): List<EpgEntity>

    @Query(
        """
        SELECT * FROM epg WHERE sourceId = :sourceId AND channelId = :channelId
          AND stop > :at ORDER BY start LIMIT :limit
        """
    )
    suspend fun upcoming(sourceId: String, channelId: String, at: Long, limit: Int): List<EpgEntity>

    @Query("SELECT COUNT(*) FROM epg WHERE sourceId = :sourceId")
    suspend fun count(sourceId: String): Int

    /** Whole guide of one channel, for the day-by-day view. */
    @Query(
        """
        SELECT * FROM epg WHERE sourceId = :sourceId AND channelId = :channelId
          AND stop > :from ORDER BY start
        """
    )
    suspend fun allFrom(sourceId: String, channelId: String, from: Long): List<EpgEntity>

    /** Span the provider actually gave us for a channel, to build the day tabs. */
    @Query(
        """
        SELECT MIN(start) FROM epg WHERE sourceId = :sourceId AND channelId = :channelId
        """
    )
    suspend fun firstStart(sourceId: String, channelId: String): Long?

    @Query("SELECT MAX(stop) FROM epg WHERE sourceId = :sourceId AND channelId = :channelId")
    suspend fun lastStop(sourceId: String, channelId: String): Long?

    /** Everything on air for a set of channels in a window: the guide wall. */
    @Query(
        """
        SELECT * FROM epg
        WHERE sourceId = :sourceId AND channelId IN (:channelIds)
          AND stop > :from AND start < :to
        ORDER BY channelId, start
        """
    )
    suspend fun forChannels(
        sourceId: String,
        channelIds: List<String>,
        from: Long,
        to: Long
    ): List<EpgEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<EpgEntity>)

    /**
     * Blocking variant: the XMLTV reader pushes batches from a plain callback
     * on a background thread and cannot suspend.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAllBlocking(items: List<EpgEntity>)

    @Query("DELETE FROM epg WHERE sourceId = :sourceId AND stop < :cutoff")
    suspend fun deleteOlderThan(sourceId: String, cutoff: Long)

    @Query("DELETE FROM epg WHERE sourceId = :sourceId")
    suspend fun clear(sourceId: String)
}
