package com.mateof.kanal.link

import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.model.SourceType
import com.mateof.kanal.data.net.redactUrl
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.repo.ContentRepository
import com.mateof.kanal.data.repo.Playable
import com.mateof.kanal.data.xtream.XtreamUrls
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The receiving end: what to do with a request once the server has read it. */
@Singleton
class LinkReceiver @Inject constructor(
    private val prefs: AppPreferences,
    private val content: ContentRepository,
    private val inbox: LinkInbox,
    private val logger: FileLogger
) {
    /**
     * Opens what was sent. When the same account is the active source here and
     * the item is in its catalogue, it opens as if picked from the list —
     * zapping, guide and history included. Anything else plays from the urls
     * the sender resolved, which carry the account with them.
     */
    suspend fun play(command: PlayCommand): PlayAnswer {
        val urls = command.urls.filter(::isNetworkUrl)
        if (urls.isEmpty()) return PlayAnswer(ok = false, error = "no playable url")

        val active = prefs.activeSource.first()
        val sameAccount = active != null && command.source != null && keyOf(active) == command.source
        if (sameAccount && active != null) {
            val (itemId, start) = splitCatchup(command.kind, command.itemId)
            if (existsHere(active.id, command.kind, itemId)) {
                if (!command.isLive && command.positionMs > 0) {
                    inbox.setStartPosition(command.kind, itemId, command.positionMs)
                }
                logger.i("Link", "${command.from.name} envía '${command.title}' (del catálogo)")
                inbox.requestPlay(IncomingPlay(command.kind, itemId, start, command.title, command.from.name))
                return PlayAnswer(ok = true, mode = MODE_CATALOG)
            }
        }

        val playable = Playable(
            kind = runCatching { ContentKind.valueOf(command.kind) }.getOrDefault(ContentKind.LIVE),
            // Blank on purpose: it marks the playable as foreign, so nothing
            // here files it in history or favourites under a source it is not from.
            sourceId = "",
            itemId = command.itemId,
            title = command.title,
            subtitle = command.subtitle,
            logo = command.logo,
            url = urls.first(),
            userAgent = command.userAgent,
            isLive = command.isLive,
            seriesId = command.seriesId,
            startPositionMs = if (command.isLive) 0L else command.positionMs,
            fallbackUrls = urls.drop(1)
        )
        val key = inbox.putRemote(playable)
        logger.i("Link", "${command.from.name} envía '${command.title}' (${redactUrl(urls.first())})")
        inbox.requestPlay(IncomingPlay(KIND_REMOTE, key, 0L, command.title, command.from.name))
        return PlayAnswer(ok = true, mode = MODE_URL)
    }

    fun stop(): StopAnswer {
        val position = inbox.nowPlaying.value?.takeIf { !it.isLive }?.positionMs ?: 0L
        inbox.requestStop()
        return StopAnswer(ok = true, positionMs = position)
    }

    /**
     * Asks the person in front of the screen, then adds what they accepted. A
     * source this device already has — same account, same server — is updated
     * in place rather than added twice, keeping its id so favourites and
     * history still point at it.
     */
    suspend fun offer(offer: SourcesOffer, host: String): SourcesAnswer {
        val incoming = offer.sources.filter { it.url.isNotBlank() }.take(MAX_SOURCES)
        if (incoming.isEmpty()) return SourcesAnswer(accepted = false)
        logger.i("Link", "${offer.from.name} ($host) ofrece ${incoming.size} fuente(s)")

        if (!inbox.ask(offer.from, host, incoming)) {
            logger.i("Link", "Fuentes de ${offer.from.name} rechazadas")
            return SourcesAnswer(accepted = false)
        }

        val existing = prefs.sources.first()
        var added = 0
        var updated = 0
        for (source in incoming) {
            val match = existing.firstOrNull { keyOf(it) == keyOf(source) }
            val cleaned = source.copy(
                id = match?.id ?: UUID.randomUUID().toString(),
                createdAt = match?.createdAt ?: System.currentTimeMillis(),
                // The sender's sync times say nothing about the catalogue here.
                lastSyncAt = match?.lastSyncAt ?: 0L,
                lastEpgSyncAt = match?.lastEpgSyncAt ?: 0L
            )
            prefs.upsertSource(cleaned)
            if (match == null) added++ else updated++
        }
        logger.i("Link", "Fuentes de ${offer.from.name}: $added nuevas, $updated actualizadas")
        inbox.markImported()
        return SourcesAnswer(accepted = true, added = added, updated = updated)
    }

    private suspend fun existsHere(sourceId: String, kind: String, itemId: String): Boolean = when (kind) {
        ContentKind.LIVE.name -> content.channel(sourceId, itemId) != null
        ContentKind.MOVIE.name -> content.movie(sourceId, itemId) != null
        ContentKind.SERIES.name -> content.episode(sourceId, itemId) != null
        else -> false
    }

    /** A catch-up programme travels as "streamId@startMillis". */
    private fun splitCatchup(kind: String, itemId: String): Pair<String, Long> {
        if (kind != ContentKind.LIVE.name || !itemId.contains('@')) return itemId to 0L
        val start = itemId.substringAfter('@').toLongOrNull() ?: return itemId to 0L
        return itemId.substringBefore('@') to start
    }

    companion object {
        const val MODE_CATALOG = "catalog"
        const val MODE_URL = "url"
        private const val MAX_SOURCES = 20

        fun keyOf(source: Source): SourceKey = SourceKey(
            type = source.type.name,
            url = if (source.type == SourceType.XTREAM) XtreamUrls.normalizeBase(source.url) else source.url.trim(),
            username = source.username.trim()
        )

        /**
         * Only what a player fetches over the network. A file or content uri
         * names something on the sender, and anything else has no business
         * being opened because somebody on the network asked.
         */
        fun isNetworkUrl(url: String): Boolean {
            val scheme = url.substringBefore("://", "").lowercase()
            return scheme in setOf("http", "https", "rtsp", "rtmp", "rtp", "udp")
        }
    }
}
