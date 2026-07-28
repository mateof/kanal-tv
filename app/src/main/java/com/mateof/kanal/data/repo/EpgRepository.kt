package com.mateof.kanal.data.repo

import com.mateof.kanal.core.firstStr
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.core.long
import com.mateof.kanal.data.db.EpgEntity
import com.mateof.kanal.data.db.KanalDatabase
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.model.SourceType
import com.mateof.kanal.data.xtream.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class NowNext(val now: EpgEntity?, val next: EpgEntity?)

@Singleton
class EpgRepository @Inject constructor(
    private val db: KanalDatabase,
    private val xtream: XtreamClient,
    private val logger: FileLogger
) {
    /**
     * What is on air right now on every channel of a source, keyed by XMLTV
     * channel id. Re-queried on a ticker because "now" moves even when the
     * database does not.
     */
    fun nowPlaying(sourceId: String): Flow<Map<String, EpgEntity>> =
        ticker(60_000).flatMapLatest {
            db.epg().nowPlaying(sourceId, System.currentTimeMillis())
                .map { list -> list.associateBy { it.channelId } }
        }

    suspend fun nowNext(sourceId: String, channelId: String): NowNext {
        if (channelId.isBlank()) return NowNext(null, null)
        val upcoming = db.epg().upcoming(sourceId, channelId, System.currentTimeMillis(), 2)
        val now = upcoming.firstOrNull { it.start <= System.currentTimeMillis() }
        val next = upcoming.firstOrNull { it.start > System.currentTimeMillis() }
        return NowNext(now, next)
    }

    suspend fun window(sourceId: String, channelId: String, from: Long, to: Long): List<EpgEntity> =
        if (channelId.isBlank()) emptyList() else db.epg().window(sourceId, channelId, from, to)

    suspend fun hasGuide(sourceId: String): Boolean = db.epg().count(sourceId) > 0

    /**
     * Days the provider actually sent guide for, starting at today. Providers
     * give anything from a few hours to a fortnight, so the tabs are built from
     * the data instead of from a fixed number.
     */
    suspend fun availableDays(sourceId: String, channelId: String): List<Long> =
        withContext(Dispatchers.IO) {
            if (channelId.isBlank()) return@withContext emptyList()
            val first = db.epg().firstStart(sourceId, channelId) ?: return@withContext emptyList()
            val last = db.epg().lastStop(sourceId, channelId) ?: return@withContext emptyList()
            val from = maxOf(first, startOfDay(System.currentTimeMillis()))
            val days = mutableListOf<Long>()
            var day = startOfDay(from)
            while (day < last && days.size < 21) {
                days += day
                day += DAY
            }
            days
        }

    /** Everything scheduled on [dayStart]'s day for one channel. */
    suspend fun programmesOfDay(
        sourceId: String,
        channelId: String,
        dayStart: Long
    ): List<EpgEntity> = withContext(Dispatchers.IO) {
        if (channelId.isBlank()) emptyList()
        else db.epg().window(sourceId, channelId, dayStart, dayStart + DAY)
    }

    /** Rows of the guide wall: programmes of many channels over one window. */
    suspend fun wall(
        sourceId: String,
        channelIds: List<String>,
        from: Long,
        to: Long
    ): Map<String, List<EpgEntity>> = withContext(Dispatchers.IO) {
        val ids = channelIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return@withContext emptyMap()
        db.epg().forChannels(sourceId, ids, from, to).groupBy { it.channelId }
    }

    /** What is on now plus the following [limit] programmes, for the OSD. */
    suspend fun upcoming(sourceId: String, channelId: String, limit: Int = 6): List<EpgEntity> =
        withContext(Dispatchers.IO) {
            if (channelId.isBlank()) emptyList()
            else db.epg().upcoming(sourceId, channelId, System.currentTimeMillis(), limit)
        }

    private fun startOfDay(millis: Long): Long {
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = millis
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    /**
     * Last resort when there is no XMLTV: Xtream's own per-channel guide.
     * Titles and descriptions come back base64-encoded.
     */
    suspend fun fetchShortEpg(source: Source, streamId: String, epgChannelId: String): List<EpgEntity> =
        withContext(Dispatchers.IO) {
            if (source.type != SourceType.XTREAM) return@withContext emptyList()
            val listings = xtream.shortEpg(source, streamId)
            val channelId = epgChannelId.ifBlank { "stream:$streamId" }
            val mapped = listings.mapNotNull { item ->
                val start = item.long("start_timestamp") * 1000L
                val stop = item.long("stop_timestamp") * 1000L
                if (start <= 0 || stop <= start) return@mapNotNull null
                EpgEntity(
                    sourceId = source.id,
                    channelId = channelId,
                    start = start,
                    stop = stop,
                    title = decodeBase64(item.firstStr("title")),
                    description = decodeBase64(item.firstStr("description"))
                )
            }
            if (mapped.isNotEmpty()) {
                runCatching { db.epg().insertAll(mapped) }
                    .onFailure { logger.w("Epg", "No se pudo guardar la guía corta", it) }
            }
            mapped
        }

    private fun decodeBase64(value: String): String {
        if (value.isEmpty()) return ""
        return runCatching {
            String(android.util.Base64.decode(value, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(value)
    }

    private companion object {
        const val DAY = 24 * 3_600_000L
    }

    private fun ticker(periodMs: Long): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(periodMs)
        }
    }
}
