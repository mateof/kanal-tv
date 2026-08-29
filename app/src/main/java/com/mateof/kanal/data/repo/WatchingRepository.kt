package com.mateof.kanal.data.repo

import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.db.ChannelEntity
import com.mateof.kanal.data.dispatcharr.DispatcharrClient
import com.mateof.kanal.data.dispatcharr.WatchedChannel
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.prefs.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/** A channel of this catalogue that the server is serving right now. */
data class WatchedNow(
    val channel: ChannelEntity,
    /** Devices on it. All of them share one connection to the provider. */
    val clients: Int,
    val since: Long
)

/**
 * What the panel is serving to the rest of the house, matched to this device's
 * catalogue so it can be opened here with one press.
 *
 * Only Dispatcharr answers this, and only when the source carries an API key.
 * Everything degrades to an empty list: no key, another panel, a revoked key or
 * a server that is simply down all look the same from the screen's side, which
 * is on purpose — this is an extra, never something that can break the app.
 */
@Singleton
class WatchingRepository @Inject constructor(
    private val client: DispatcharrClient,
    private val content: ContentRepository,
    private val prefs: AppPreferences,
    private val logger: FileLogger
) {
    private class Catalogue(val idsByUuid: Map<String, String>, val readAt: Long)

    private val catalogues = HashMap<String, Catalogue>()

    /** Last failure per source, so a wrong key is logged once and not every poll. */
    private val lastFailure = HashMap<String, String>()

    fun watching(source: Source?): Flow<List<WatchedNow>> = flow {
        if (source == null || source.apiKey.isBlank()) {
            emit(emptyList())
            return@flow
        }
        while (true) {
            emit(load(source))
            delay(POLL_MS)
        }
    }

    private suspend fun load(source: Source): List<WatchedNow> {
        val watched = runCatching { client.watching(source) }.getOrElse { error ->
            val message = error.message.orEmpty()
            if (lastFailure[source.id] != message) {
                lastFailure[source.id] = message
                logger.w("Dispatcharr", "No se pudo leer lo que se está viendo: $message")
            }
            return emptyList()
        }
        lastFailure.remove(source.id)
        if (watched.isEmpty()) return emptyList()

        val channels = resolve(source, watched)
        val hideAdult = prefs.settings.first().hideAdult
        val hidden = prefs.hiddenChannels.first()
        return watched.mapNotNull { entry ->
            val channel = channels[entry.name] ?: return@mapNotNull null
            // What is hidden here stays hidden here, whatever another device in
            // the house happens to be watching.
            if (hideAdult && channel.adult) return@mapNotNull null
            if ("${source.id}:${channel.streamId}" in hidden) return@mapNotNull null
            WatchedNow(channel, entry.clients, entry.startedAt)
        }
    }

    /**
     * Matching is by name first because it is the one thing both catalogues
     * always agree on: an M3U playlist numbers its channels by url hash, so the
     * panel's ids mean nothing there. Only when a name finds nothing — a
     * renamed channel — is the panel asked for its uuid → id map, which is the
     * exact answer for an Xtream source but costs a whole catalogue to fetch.
     */
    private suspend fun resolve(
        source: Source,
        watched: List<WatchedChannel>
    ): Map<String, ChannelEntity> {
        val byName = content.channelsByNames(source.id, watched.map { it.name })
            .associateBy { it.name }
        val missing = watched.filter { it.name !in byName }
        if (missing.isEmpty() || !source.isXtream) return byName

        val ids = catalogue(source) ?: return byName
        val wanted = missing.mapNotNull { ids[it.uuid] }
        val byId = content.channelsByIds(source.id, wanted).associateBy { it.streamId }
        return byName + missing.mapNotNull { entry ->
            val channel = byId[ids[entry.uuid]] ?: return@mapNotNull null
            entry.name to channel
        }
    }

    private suspend fun catalogue(source: Source): Map<String, String>? {
        val cached = catalogues[source.id]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.readAt < CATALOGUE_TTL_MS) return cached.idsByUuid
        val fresh = runCatching { client.channelIdsByUuid(source) }.getOrElse { error ->
            logger.w("Dispatcharr", "No se pudo leer el catálogo del panel: ${error.message}")
            // Remembered as empty so a panel that will not answer is asked once
            // per period and not on every poll.
            emptyMap()
        }
        catalogues[source.id] = Catalogue(fresh, now)
        return fresh.ifEmpty { null }
    }

    private companion object {
        /** Often enough to feel live, rarely enough to be nothing on a server. */
        const val POLL_MS = 10_000L
        const val CATALOGUE_TTL_MS = 30 * 60_000L
    }
}
