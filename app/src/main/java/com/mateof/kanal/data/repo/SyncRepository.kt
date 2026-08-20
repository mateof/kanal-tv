package com.mateof.kanal.data.repo

import com.mateof.kanal.R
import com.mateof.kanal.core.UiText
import com.mateof.kanal.core.asArrayOrEmpty
import com.mateof.kanal.core.asObject
import com.mateof.kanal.core.bool
import com.mateof.kanal.core.double
import com.mateof.kanal.core.firstStr
import com.mateof.kanal.core.int
import android.content.Context
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.core.long
import com.mateof.kanal.core.normalizedForSearch
import com.mateof.kanal.core.sortKeyOf
import com.mateof.kanal.data.db.CategoryEntity
import com.mateof.kanal.data.db.ChannelEntity
import com.mateof.kanal.data.db.EpgEntity
import com.mateof.kanal.data.db.EpisodeEntity
import com.mateof.kanal.data.db.KanalDatabase
import com.mateof.kanal.data.db.MovieEntity
import com.mateof.kanal.data.db.SeriesEntity
import com.mateof.kanal.data.epg.XmltvParser
import com.mateof.kanal.data.m3u.M3uEntry
import com.mateof.kanal.data.m3u.M3uKind
import com.mateof.kanal.data.m3u.M3uParser
import com.mateof.kanal.data.m3u.parseEpisodeName
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.model.SourceType
import com.mateof.kanal.data.net.HttpProvider
import com.mateof.kanal.data.net.redactUrl
import com.mateof.kanal.data.logos.LogoCatalog
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.xtream.XtreamCatalog
import com.mateof.kanal.data.xtream.XtreamClient
import com.mateof.kanal.data.xtream.XtreamUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class SyncSummary(
    val channels: Int = 0,
    val movies: Int = 0,
    val series: Int = 0,
    val programmes: Int = 0
)

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val step: UiText, val progress: Float = -1f) : SyncState
    data class Done(val at: Long, val summary: SyncSummary) : SyncState
    data class Failed(val message: UiText) : SyncState
}

/**
 * Pulls a provider's catalogue and guide into the local database.
 *
 * The database is a disposable cache: each sync replaces the previous content
 * for that source, so a channel the provider drops disappears here too.
 */
@Singleton
class SyncRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: KanalDatabase,
    private val xtream: XtreamClient,
    private val http: HttpProvider,
    private val prefs: AppPreferences,
    private val logos: LogoCatalog,
    private val accounts: AccountRepository,
    private val logger: FileLogger
) {
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val m3uParser = M3uParser()
    private val xmltvParser = XmltvParser()

    suspend fun syncAll(source: Source, includeEpg: Boolean = true): SyncState =
        withContext(Dispatchers.IO) {
            try {
                logger.i("Sync", "Sincronizando '${source.name}' (${source.type})")
                val startedAt = System.currentTimeMillis()
                val content = when (source.type) {
                    SourceType.XTREAM -> syncXtream(source)
                    SourceType.M3U -> syncM3u(source)
                }
                prefs.markSynced(source.id, contentAt = System.currentTimeMillis())

                var summary = content
                if (includeEpg) {
                    // syncM3u may have discovered a guide url; re-read the source.
                    val refreshed = prefs.sources.first().firstOrNull { it.id == source.id } ?: source
                    val programmes = runCatching { syncEpg(refreshed) }.getOrElse {
                        logger.w("Sync", "La guía no se pudo actualizar", it)
                        0
                    }
                    summary = summary.copy(programmes = programmes)
                }

                logger.i(
                    "Sync",
                    "Listo en ${System.currentTimeMillis() - startedAt} ms: " +
                        "${summary.channels} canales, ${summary.movies} películas, " +
                        "${summary.series} series, ${summary.programmes} programas"
                )
                SyncState.Done(System.currentTimeMillis(), summary).also { _state.value = it }
            } catch (e: Exception) {
                logger.e("Sync", "Sincronización fallida", e)
                SyncState.Failed(
                    e.message?.let { UiText(R.string.sync_failed, it) }
                        ?: UiText(R.string.sync_failed_unknown)
                ).also { _state.value = it }
            }
        }

    // --- Xtream --------------------------------------------------------------

    private suspend fun syncXtream(source: Source): SyncSummary {
        step(UiText(R.string.sync_checking_account))
        val account = xtream.authenticate(source)
        logger.i(
            "Sync",
            "Cuenta ${account.username}: ${account.status}, " +
                "${account.activeConnections}/${account.maxConnections} conexiones"
        )
        accounts.remember(
            AccountStatus(
                sourceId = source.id,
                username = account.username,
                status = account.status,
                isActive = account.isActive,
                activeConnections = account.activeConnections,
                maxConnections = account.maxConnections,
                expiresAt = account.expiresAt,
                checkedAt = System.currentTimeMillis()
            )
        )

        step(UiText(R.string.sync_categories), 0.05f)
        val liveNames = storeCategories(source.id, "LIVE", xtream.categories(source, XtreamCatalog.LIVE))
        val vodNames = storeCategories(source.id, "MOVIE", xtream.categories(source, XtreamCatalog.VOD))
        val seriesNames = storeCategories(source.id, "SERIES", xtream.categories(source, XtreamCatalog.SERIES))

        step(UiText(R.string.sync_channels), 0.15f)
        val channels = xtream.items(source, XtreamCatalog.LIVE, liveNames.keys.toList())
            .mapIndexedNotNull { index, item ->
                val streamId = item.firstStr("stream_id", "id")
                if (streamId.isEmpty()) return@mapIndexedNotNull null
                val name = item.firstStr("name", "title", fallback = "Canal $streamId")
                val categoryId = item.firstStr("category_id")
                val categoryName = liveNames[categoryId].orEmpty()
                ChannelEntity(
                    sourceId = source.id,
                    streamId = streamId,
                    name = name,
                    sortName = sortKeyOf(name),
                    logo = item.firstStr("stream_icon", "cover", "icon"),
                    categoryId = categoryId,
                    categoryName = categoryName,
                    epgChannelId = item.firstStr("epg_channel_id", "epg_id"),
                    number = item.int("num", index + 1),
                    archiveDays = if (item.bool("tv_archive")) item.int("tv_archive_duration", 0) else 0,
                    adult = item.bool("is_adult") || isAdult(categoryName),
                    position = index
                )
            }
        if (changed(source.id, "live", channels.map { it.streamId to it.name })) {
            replaceChannels(source.id, channels)
        }

        step(UiText(R.string.sync_movies), 0.45f)
        val movies = xtream.items(source, XtreamCatalog.VOD, vodNames.keys.toList())
            .mapIndexedNotNull { index, item ->
                val streamId = item.firstStr("stream_id", "id")
                if (streamId.isEmpty()) return@mapIndexedNotNull null
                val name = item.firstStr("name", "title", fallback = "Película $streamId")
                val categoryId = item.firstStr("category_id")
                val categoryName = vodNames[categoryId].orEmpty()
                MovieEntity(
                    sourceId = source.id,
                    streamId = streamId,
                    name = name,
                    sortName = sortKeyOf(name),
                    cover = item.firstStr("stream_icon", "cover", "movie_image"),
                    categoryId = categoryId,
                    categoryName = categoryName,
                    containerExtension = item.firstStr("container_extension", fallback = "mp4"),
                    rating = item.double("rating"),
                    addedAt = item.long("added") * 1000L,
                    adult = item.bool("is_adult") || isAdult(categoryName),
                    position = index
                )
            }
        if (changed(source.id, "vod", movies.map { it.streamId to it.name })) {
            replaceMovies(source.id, movies)
        }

        step(UiText(R.string.sync_series), 0.75f)
        val series = xtream.items(source, XtreamCatalog.SERIES, seriesNames.keys.toList())
            .mapIndexedNotNull { index, item ->
                val seriesId = item.firstStr("series_id", "id")
                if (seriesId.isEmpty()) return@mapIndexedNotNull null
                val name = item.firstStr("name", "title", fallback = "Serie $seriesId")
                val categoryId = item.firstStr("category_id")
                val categoryName = seriesNames[categoryId].orEmpty()
                SeriesEntity(
                    sourceId = source.id,
                    seriesId = seriesId,
                    name = name,
                    sortName = sortKeyOf(name),
                    cover = item.firstStr("cover", "stream_icon", "cover_big"),
                    categoryId = categoryId,
                    categoryName = categoryName,
                    plot = item.firstStr("plot", "description"),
                    genre = item.firstStr("genre"),
                    cast = item.firstStr("cast", "actors"),
                    director = item.firstStr("director"),
                    releaseDate = item.firstStr("releaseDate", "release_date", "releasedate"),
                    rating = item.double("rating"),
                    lastModified = item.long("last_modified") * 1000L,
                    adult = isAdult(categoryName),
                    position = index
                )
            }
        if (changed(source.id, "series", series.map { it.seriesId to it.name })) {
            replaceSeries(source.id, series)
        }
        db.episodes().clear(source.id)

        return SyncSummary(channels.size, movies.size, series.size)
    }

    private suspend fun storeCategories(
        sourceId: String,
        kind: String,
        raw: List<JsonObject>
    ): Map<String, String> {
        val entities = raw.mapIndexedNotNull { index, item ->
            val id = item.firstStr("category_id", "id")
            if (id.isEmpty()) return@mapIndexedNotNull null
            CategoryEntity(
                sourceId = sourceId,
                kind = kind,
                categoryId = id,
                name = item.firstStr("category_name", "name", fallback = "Sin nombre"),
                position = index
            )
        }
        db.categories().replace(sourceId, kind, entities)
        return entities.associate { it.categoryId to it.name }
    }

    /** Episodes of one series, fetched lazily by the detail screen (Xtream only). */
    suspend fun loadSeriesEpisodes(source: Source, seriesId: String): Int =
        withContext(Dispatchers.IO) {
            if (source.type != SourceType.XTREAM) return@withContext 0
            val info = xtream.seriesInfo(source, seriesId) ?: return@withContext 0

            val buckets: List<JsonElement> = when (val raw = info["episodes"]) {
                is JsonObject -> raw.values.toList()
                is JsonArray -> raw.toList()
                else -> emptyList()
            }

            val episodes = ArrayList<EpisodeEntity>()
            buckets.forEach { bucket ->
                bucket.asArrayOrEmpty().forEach { element ->
                    val item = element.asObject() ?: return@forEach
                    val episodeId = item.firstStr("id", "episode_id")
                    if (episodeId.isEmpty()) return@forEach
                    val details = item["info"].asObject()
                    val number = item.int("episode_num", 0)
                    episodes += EpisodeEntity(
                        sourceId = source.id,
                        episodeId = episodeId,
                        seriesId = seriesId,
                        season = item.int("season", 1),
                        number = number,
                        title = item.firstStr("title", fallback = "Episodio $number"),
                        plot = details?.firstStr("plot", "description").orEmpty(),
                        cover = details?.firstStr("movie_image", "cover_big", "image").orEmpty(),
                        containerExtension = item.firstStr("container_extension", fallback = "mp4"),
                        durationSecs = details?.int("duration_secs") ?: 0,
                        airDate = details?.firstStr("releasedate", "release_date").orEmpty()
                    )
                }
            }
            if (episodes.isNotEmpty()) db.episodes().replaceSeries(source.id, seriesId, episodes)
            logger.d("Sync", "Serie $seriesId: ${episodes.size} episodios")
            episodes.size
        }

    // --- M3U -----------------------------------------------------------------

    private suspend fun syncM3u(source: Source): SyncSummary {
        step(UiText(R.string.sync_list))
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", source.userAgent.ifBlank { "Kanal/1.0" })
            .build()

        val channels = ArrayList<ChannelEntity>()
        val movies = ArrayList<MovieEntity>()
        val showEntries = LinkedHashMap<String, MutableList<M3uEntry>>()
        var advertisedEpg = ""
        var index = 0

        http.longRunningClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "La lista respondió ${response.code}. Comprueba la URL: ${redactUrl(source.url)}"
                )
            }
            val body = response.body ?: throw IllegalStateException("La lista vino vacía.")
            advertisedEpg = BufferedReader(InputStreamReader(body.byteStream()), 64 * 1024).use { reader ->
                m3uParser.parse(reader) { entry ->
                    when (entry.kind) {
                        M3uKind.LIVE -> channels += entry.toChannel(source.id, index)
                        M3uKind.MOVIE -> movies += entry.toMovie(source.id, index)
                        M3uKind.SERIES -> {
                            val parsed = parseEpisodeName(entry.name)
                            if (parsed == null) {
                                movies += entry.toMovie(source.id, index)
                            } else {
                                showEntries.getOrPut(parsed.show) { mutableListOf() } += entry
                            }
                        }
                    }
                    index++
                    if (index % 5_000 == 0) step(UiText(R.string.sync_reading_list, index))
                }
            }
        }

        step(UiText(R.string.sync_saving_channels), 0.6f)
        db.categories().replace(
            source.id, "LIVE", categoriesOf(source.id, "LIVE", channels.map { it.categoryName })
        )
        db.categories().replace(
            source.id, "MOVIE", categoriesOf(source.id, "MOVIE", movies.map { it.categoryName })
        )
        if (changed(source.id, "live", channels.map { it.streamId to it.name })) {
            replaceChannels(source.id, channels)
        }
        if (changed(source.id, "vod", movies.map { it.streamId to it.name })) {
            replaceMovies(source.id, movies)
        }

        val series = ArrayList<SeriesEntity>()
        val episodes = ArrayList<EpisodeEntity>()
        showEntries.entries.forEachIndexed { position, entry ->
            val show = entry.key
            val entries = entry.value
            val seriesId = stableId(show)
            val first = entries.first()
            series += SeriesEntity(
                sourceId = source.id,
                seriesId = seriesId,
                name = show,
                sortName = sortKeyOf(show),
                cover = first.logo,
                categoryId = stableId(first.group),
                categoryName = first.group,
                adult = isAdult(first.group),
                position = position
            )
            entries.forEach { item ->
                val parsed = parseEpisodeName(item.name) ?: return@forEach
                episodes += EpisodeEntity(
                    sourceId = source.id,
                    episodeId = stableId(item.url),
                    seriesId = seriesId,
                    season = parsed.season,
                    number = parsed.episode,
                    title = parsed.title.ifBlank { "Episodio ${parsed.episode}" },
                    cover = item.logo,
                    containerExtension = item.url.substringAfterLast('.', "mp4").take(5),
                    url = item.url
                )
            }
        }
        db.categories().replace(
            source.id, "SERIES", categoriesOf(source.id, "SERIES", series.map { it.categoryName })
        )
        replaceSeries(source.id, series)
        db.episodes().clear(source.id)
        episodes.chunked(BATCH).forEach { db.episodes().insertAll(it) }

        if (advertisedEpg.isNotBlank() && source.epgUrl.isBlank()) {
            logger.i("Sync", "La lista anuncia guía en ${redactUrl(advertisedEpg)}")
            prefs.upsertSource(source.copy(epgUrl = advertisedEpg))
        }

        return SyncSummary(channels.size, movies.size, series.size)
    }

    private fun categoriesOf(sourceId: String, kind: String, names: List<String>): List<CategoryEntity> =
        names.filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .mapIndexed { index, name ->
                CategoryEntity(
                    sourceId = sourceId,
                    kind = kind,
                    categoryId = stableId(name),
                    name = name,
                    position = index
                )
            }

    private fun M3uEntry.toChannel(sourceId: String, index: Int) = ChannelEntity(
        sourceId = sourceId,
        streamId = stableId(url),
        name = name,
        sortName = sortKeyOf(name),
        logo = logo,
        categoryId = stableId(group),
        categoryName = group,
        epgChannelId = tvgId,
        number = if (channelNumber > 0) channelNumber else index + 1,
        url = url,
        archiveDays = catchupDays,
        adult = isAdult(group),
        position = index
    )

    private fun M3uEntry.toMovie(sourceId: String, index: Int) = MovieEntity(
        sourceId = sourceId,
        streamId = stableId(url),
        name = name,
        sortName = sortKeyOf(name),
        cover = logo,
        categoryId = stableId(group),
        categoryName = group,
        containerExtension = url.substringAfterLast('.', "mp4").take(5),
        url = url,
        adult = isAdult(group),
        position = index
    )

    // --- EPG -----------------------------------------------------------------

    /** @return how many programmes were stored. */
    suspend fun syncEpg(source: Source): Int = withContext(Dispatchers.IO) {
        val url = when (source.type) {
            SourceType.XTREAM -> XtreamUrls.xmltv(source)
            SourceType.M3U -> source.epgUrl
        }
        if (url.isBlank()) {
            logger.i("Epg", "La fuente '${source.name}' no tiene guía configurada")
            return@withContext 0
        }

        step(UiText(R.string.sync_guide), 0.85f)
        val settings = prefs.settings.first()
        val now = System.currentTimeMillis()
        val from = now - 12 * HOUR
        val to = now + settings.epgDaysAhead * 24 * HOUR

        val knownIds = db.channels().epgIds(source.id).toHashSet()
        val nameIndex = db.channels().withoutEpgId(source.id)
            .associateBy { it.name.normalizedForSearch() }
        val matchedByName = HashMap<String, ChannelEntity>()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", source.userAgent.ifBlank { "Kanal/1.0" })
            .build()

        var stored = 0
        val buffer = ArrayList<EpgEntity>(BATCH)

        http.longRunningClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("La guía respondió ${response.code}.")
            }
            val body = response.body ?: throw IllegalStateException("La guía vino vacía.")

            db.epg().clear(source.id)
            xmltvParser.parse(
                body.byteStream(),
                onChannel = { channel ->
                    if (channel.id !in knownIds) {
                        // The playlist had no tvg-id: match on the display name.
                        for (display in channel.displayNames) {
                            val match = nameIndex[display.normalizedForSearch()] ?: continue
                            if (match.streamId !in matchedByName) {
                                matchedByName[match.streamId] = match.copy(epgChannelId = channel.id)
                                knownIds += channel.id
                            }
                        }
                    }
                },
                onProgramme = { programme ->
                    if (programme.channelId in knownIds &&
                        programme.stop > from && programme.start < to
                    ) {
                        buffer += programme.let {
                            EpgEntity(
                                sourceId = source.id,
                                channelId = it.channelId,
                                start = it.start,
                                stop = it.stop,
                                title = it.title,
                                description = it.description,
                                category = it.category
                            )
                        }
                        if (buffer.size >= BATCH) {
                            stored += buffer.size
                            db.epg().insertAllBlocking(buffer.toList())
                            buffer.clear()
                        }
                    }
                }
            )
        }
        if (buffer.isNotEmpty()) {
            stored += buffer.size
            db.epg().insertAll(buffer.toList())
        }

        if (matchedByName.isNotEmpty()) {
            logger.i("Epg", "${matchedByName.size} canales emparejados por nombre")
            matchedByName.values.chunked(BATCH).forEach { db.channels().insertAll(it) }
        }

        db.epg().deleteOlderThan(source.id, from)
        prefs.markSynced(source.id, epgAt = System.currentTimeMillis())
        logger.i("Epg", "Guía de '${source.name}': $stored programas")
        stored
    }

    /**
     * Whether a catalogue is worth writing to the database again.
     *
     * Sixteen thousand films arrive on every sync and almost never differ. The
     * fetch cannot be avoided — the panel offers no way to ask "has anything
     * changed?" — but clearing and reinserting the lot can be, and that is the
     * part that makes an idle sync take the best part of a minute.
     *
     * The fingerprint is over ids and names, which is what the app shows and
     * searches. A film whose rating moved will not force a rewrite, and that is
     * the intended bargain.
     */
    private suspend fun changed(
        sourceId: String,
        catalogue: String,
        items: List<Pair<String, String>>
    ): Boolean {
        val fingerprint = fingerprintOf(items)
        val key = "$sourceId:$catalogue"
        val previous = prefs.catalogMarks.first()[key]
        if (previous == fingerprint) {
            logger.i("Sync", "'$catalogue' sin cambios (${items.size}), no se reescribe")
            return false
        }
        prefs.markCatalog(key, fingerprint)
        return true
    }

    private fun fingerprintOf(items: List<Pair<String, String>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        items.forEach { (id, name) ->
            digest.update(id.toByteArray())
            digest.update(name.toByteArray())
        }
        return items.size.toString() + ":" +
            digest.digest().joinToString("") { "%02x".format(it) }
    }

    // --- Shared --------------------------------------------------------------

    private suspend fun replaceChannels(sourceId: String, items: List<ChannelEntity>) {
        db.channels().clear(sourceId)
        items.chunked(BATCH).forEach { db.channels().insertAll(it) }
        fillMissingLogos(sourceId)
    }

    /**
     * Gives a logo to the channels the playlist left without one.
     *
     * Only ever fills blanks: whatever the provider sent is what the user
     * expects to see, and is usually the right artwork for that exact feed.
     */
    private suspend fun fillMissingLogos(sourceId: String) {
        if (!prefs.settings.first().fillMissingLogos) return
        val blanks = db.channels().withoutLogo(sourceId)
        if (blanks.isEmpty()) return

        // The device's country, not the language the interface is set to: a
        // Galician interface on a Spanish television still wants Spanish logos.
        val country = context.resources.configuration.locales[0].country
            .ifBlank { Locale.getDefault().country }
        val catalogue = logos.forCountry(country)
        if (catalogue.isEmpty()) return

        val filled = blanks.mapNotNull { channel ->
            logos.match(catalogue, channel.name)?.let { channel.copy(logo = it) }
        }
        if (filled.isEmpty()) {
            logger.i("Logos", "Ningún nombre coincidió con el catálogo de '$country'")
            return
        }
        filled.chunked(BATCH).forEach { db.channels().insertAll(it) }
        logger.i("Logos", "Logotipos añadidos: ${filled.size} de ${blanks.size} sin él")
    }

    private suspend fun replaceMovies(sourceId: String, items: List<MovieEntity>) {
        db.movies().clear(sourceId)
        items.chunked(BATCH).forEach { db.movies().insertAll(it) }
    }

    private suspend fun replaceSeries(sourceId: String, items: List<SeriesEntity>) {
        db.series().clear(sourceId)
        items.chunked(BATCH).forEach { db.series().insertAll(it) }
    }

    private fun step(text: UiText, progress: Float = -1f) {
        _state.value = SyncState.Running(text, progress)
    }

    private companion object {
        const val BATCH = 500
        const val HOUR = 3_600_000L

        val ADULT_HINTS = listOf("xxx", "adult", "porn", "+18", "18+", "erotic")

        fun isAdult(categoryName: String): Boolean {
            val name = categoryName.lowercase()
            return ADULT_HINTS.any { name.contains(it) }
        }

        /** Deterministic id derived from a string, stable across syncs. */
        fun stableId(value: String): String = abs(value.hashCode()).toString()
    }
}
