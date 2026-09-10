package com.mateof.kanal.data.download

import android.content.Context
import android.content.Intent
import android.os.Build
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.db.EpisodeEntity
import com.mateof.kanal.data.db.MovieEntity
import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.prefs.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Why a download could not be queued, in words the screen can show. */
sealed interface EnqueueResult {
    data object Queued : EnqueueResult
    data object AlreadyThere : EnqueueResult
    data class NoRoom(val neededBytes: Long) : EnqueueResult
    data class OverLimit(val limitGb: Int) : EnqueueResult
}

@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val storage: DownloadStorage,
    private val logger: FileLogger
) {
    val downloads: Flow<List<DownloadItem>> = prefs.downloads

    suspend fun enqueueMovie(source: Source, movie: MovieEntity): EnqueueResult = enqueue(
        DownloadItem(
            id = downloadId(ContentKind.MOVIE, source.id, movie.streamId),
            sourceId = source.id,
            kind = ContentKind.MOVIE,
            itemId = movie.streamId,
            title = movie.name,
            subtitle = movie.categoryName,
            cover = movie.cover,
            fileName = safeFileName(movie.name, movie.containerExtension),
            createdAt = System.currentTimeMillis()
        )
    )

    suspend fun enqueueEpisode(
        source: Source,
        episode: EpisodeEntity,
        seriesName: String
    ): EnqueueResult = enqueue(
        DownloadItem(
            id = downloadId(ContentKind.SERIES, source.id, episode.episodeId),
            sourceId = source.id,
            kind = ContentKind.SERIES,
            itemId = episode.episodeId,
            title = episodeLabel(episode),
            subtitle = seriesName,
            cover = episode.cover,
            seriesId = episode.seriesId,
            seriesName = seriesName,
            fileName = safeFileName(
                "$seriesName ${episodeLabel(episode)}",
                episode.containerExtension
            ),
            createdAt = System.currentTimeMillis()
        )
    )

    /** A whole season at once; already-downloaded episodes are simply skipped. */
    suspend fun enqueueAll(
        source: Source,
        episodes: List<EpisodeEntity>,
        seriesName: String
    ): Int {
        var queued = 0
        for (episode in episodes) {
            if (enqueueEpisode(source, episode, seriesName) is EnqueueResult.Queued) queued++
        }
        return queued
    }

    private suspend fun enqueue(item: DownloadItem): EnqueueResult {
        val existing = prefs.downloadsNow().firstOrNull { it.id == item.id }
        if (existing != null && (existing.isDone || existing.isActive)) {
            return EnqueueResult.AlreadyThere
        }
        // Nothing is known about the size until the server answers, so this is
        // the coarse guard: somewhere to put a film at all, and the ceiling the
        // user set for the folder as a whole.
        if (storage.freeBytes() < MIN_FREE_BYTES) {
            return EnqueueResult.NoRoom(MIN_FREE_BYTES)
        }
        val limitGb = prefs.settings.first().downloadLimitGb
        if (limitGb > 0 && usedBytes() >= limitGb.toLong() * GB) {
            return EnqueueResult.OverLimit(limitGb)
        }
        prefs.upsertDownload(item.copy(state = DownloadState.QUEUED, error = "", bytes = 0))
        start()
        return EnqueueResult.Queued
    }

    suspend fun usedBytes(): Long = prefs.downloadsNow().sumOf { it.bytes }

    suspend fun retry(id: String) {
        val item = prefs.downloadsNow().firstOrNull { it.id == id } ?: return
        prefs.upsertDownload(item.copy(state = DownloadState.QUEUED, error = ""))
        start()
    }

    /**
     * Stops a transfer without losing it: the bytes already written stay on
     * disk and the entry goes back to the queue, so pressing again carries on
     * from there with a Range request rather than starting the film over.
     */
    fun pause(id: String) {
        DownloadService.cancel(context, id)
    }

    /** Stops it if it is running, and forgets it. The part-file goes too. */
    suspend fun remove(id: String) {
        val item = prefs.downloadsNow().firstOrNull { it.id == id } ?: return
        DownloadService.cancel(context, id)
        if (item.target.isNotBlank() && !storage.delete(item.target)) {
            logger.w("Descargas", "'${item.title}' se quita de la lista pero el fichero sigue ahí")
        }
        prefs.removeDownload(id)
    }

    suspend fun removeSeries(seriesId: String, sourceId: String) {
        prefs.downloadsNow()
            .filter { it.seriesId == seriesId && it.sourceId == sourceId }
            .forEach { remove(it.id) }
    }

    /** Called when something has been watched to the end. */
    suspend fun onWatched(kind: ContentKind, sourceId: String, itemId: String) {
        if (!prefs.settings.first().downloadDeleteWatched) return
        val id = downloadId(kind, sourceId, itemId)
        if (prefs.downloadsNow().any { it.id == id && it.isDone }) {
            logger.i("Descargas", "Borrada tras verse entera")
            remove(id)
        }
    }

    fun start() {
        val intent = Intent(context, DownloadService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun episodeLabel(episode: EpisodeEntity): String =
        "${episode.season}x${episode.number.toString().padStart(2, '0')} ${episode.title}".trim()

    private companion object {
        const val GB = 1024L * 1024 * 1024
        /** Below this there is no point starting: a film will not fit. */
        const val MIN_FREE_BYTES = 600L * 1024 * 1024
    }
}
