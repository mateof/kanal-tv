package com.mateof.kanal.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mateof.kanal.core.log.Klog
import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.data.model.HistoryItem
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.model.favoriteKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kanal")

enum class StreamFormat(val label: String, val extension: String) {
    TS("MPEG-TS (.ts)", "ts"),
    HLS("HLS (.m3u8)", "m3u8")
}

enum class BufferProfile(val label: String, val minBufferMs: Int, val maxBufferMs: Int, val startMs: Int) {
    LOW("Bajo (zapping rápido)", 1_500, 15_000, 800),
    NORMAL("Normal", 5_000, 30_000, 1_500),
    HIGH("Alto (conexión inestable)", 15_000, 60_000, 3_000)
}

data class Settings(
    val streamFormat: StreamFormat = StreamFormat.TS,
    val previewEnabled: Boolean = true,
    val previewDelayMs: Int = 1_200,
    val bufferProfile: BufferProfile = BufferProfile.NORMAL,
    val autoUpdate: Boolean = true,
    val userAgent: String = DEFAULT_USER_AGENT,
    val epgDaysAhead: Int = 3,
    val hideAdult: Boolean = false,
    val externalPlayer: String = "",
    val verboseHttpLog: Boolean = false,
    val autoSyncHours: Int = 12
)

const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object Keys {
        val SOURCES = stringPreferencesKey("sources")
        val ACTIVE_SOURCE = stringPreferencesKey("active_source")
        val FAVORITES = stringPreferencesKey("favorites")
        val HISTORY = stringPreferencesKey("history")

        val STREAM_FORMAT = stringPreferencesKey("stream_format")
        val PREVIEW_ENABLED = booleanPreferencesKey("preview_enabled")
        val PREVIEW_DELAY = intPreferencesKey("preview_delay")
        val BUFFER_PROFILE = stringPreferencesKey("buffer_profile")
        val AUTO_UPDATE = booleanPreferencesKey("auto_update")
        val USER_AGENT = stringPreferencesKey("user_agent")
        val EPG_DAYS = intPreferencesKey("epg_days")
        val HIDE_ADULT = booleanPreferencesKey("hide_adult")
        val EXTERNAL_PLAYER = stringPreferencesKey("external_player")
        val VERBOSE_HTTP = booleanPreferencesKey("verbose_http")
        val AUTO_SYNC_HOURS = intPreferencesKey("auto_sync_hours")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
    }

    // --- Sources -------------------------------------------------------------

    val sources: Flow<List<Source>> = context.dataStore.data.map { prefs ->
        decode(prefs[Keys.SOURCES], emptyList())
    }

    val activeSourceId: Flow<String?> = context.dataStore.data.map { it[Keys.ACTIVE_SOURCE] }

    val activeSource: Flow<Source?> = context.dataStore.data.map { prefs ->
        val all = decode<List<Source>>(prefs[Keys.SOURCES], emptyList())
        val active = prefs[Keys.ACTIVE_SOURCE]
        all.firstOrNull { it.id == active } ?: all.firstOrNull()
    }

    suspend fun upsertSource(source: Source) {
        context.dataStore.edit { prefs ->
            val all = decode<List<Source>>(prefs[Keys.SOURCES], emptyList()).toMutableList()
            val index = all.indexOfFirst { it.id == source.id }
            if (index >= 0) all[index] = source else all += source
            prefs[Keys.SOURCES] = json.encodeToString(all)
            if (prefs[Keys.ACTIVE_SOURCE].isNullOrBlank()) prefs[Keys.ACTIVE_SOURCE] = source.id
        }
    }

    suspend fun deleteSource(id: String) {
        context.dataStore.edit { prefs ->
            val all = decode<List<Source>>(prefs[Keys.SOURCES], emptyList()).filterNot { it.id == id }
            prefs[Keys.SOURCES] = json.encodeToString(all)
            if (prefs[Keys.ACTIVE_SOURCE] == id) {
                val next = all.firstOrNull()?.id
                if (next == null) prefs.remove(Keys.ACTIVE_SOURCE) else prefs[Keys.ACTIVE_SOURCE] = next
            }
        }
    }

    suspend fun setActiveSource(id: String) {
        context.dataStore.edit { it[Keys.ACTIVE_SOURCE] = id }
    }

    suspend fun markSynced(id: String, contentAt: Long? = null, epgAt: Long? = null) {
        context.dataStore.edit { prefs ->
            val all = decode<List<Source>>(prefs[Keys.SOURCES], emptyList()).map {
                if (it.id != id) it
                else it.copy(
                    lastSyncAt = contentAt ?: it.lastSyncAt,
                    lastEpgSyncAt = epgAt ?: it.lastEpgSyncAt
                )
            }
            prefs[Keys.SOURCES] = json.encodeToString(all)
        }
    }

    // --- Favourites ----------------------------------------------------------

    val favorites: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        decode<List<String>>(prefs[Keys.FAVORITES], emptyList()).toSet()
    }

    suspend fun toggleFavorite(kind: ContentKind, sourceId: String, itemId: String): Boolean {
        val key = favoriteKey(kind, sourceId, itemId)
        var nowFavorite = false
        context.dataStore.edit { prefs ->
            val all = decode<List<String>>(prefs[Keys.FAVORITES], emptyList()).toMutableSet()
            nowFavorite = all.add(key)
            if (!nowFavorite) all.remove(key)
            prefs[Keys.FAVORITES] = json.encodeToString(all.toList())
        }
        return nowFavorite
    }

    // --- History -------------------------------------------------------------

    val history: Flow<List<HistoryItem>> = context.dataStore.data.map { prefs ->
        decode<List<HistoryItem>>(prefs[Keys.HISTORY], emptyList())
            .sortedByDescending { it.playedAt }
    }

    suspend fun recordPlayback(item: HistoryItem) {
        context.dataStore.edit { prefs ->
            val all = decode<List<HistoryItem>>(prefs[Keys.HISTORY], emptyList())
                .filterNot { it.key == item.key }
                .toMutableList()
            all.add(0, item)
            prefs[Keys.HISTORY] = json.encodeToString(all.take(MAX_HISTORY))
        }
    }

    suspend fun resumePositionOf(key: String): Long =
        history.first().firstOrNull { it.key == key && !it.isFinished }?.positionMs ?: 0L

    suspend fun clearHistory() {
        context.dataStore.edit { it[Keys.HISTORY] = json.encodeToString(emptyList<HistoryItem>()) }
    }

    // --- Settings ------------------------------------------------------------

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            streamFormat = prefs[Keys.STREAM_FORMAT]?.let { name ->
                StreamFormat.entries.firstOrNull { it.name == name }
            } ?: StreamFormat.TS,
            previewEnabled = prefs[Keys.PREVIEW_ENABLED] ?: true,
            previewDelayMs = prefs[Keys.PREVIEW_DELAY] ?: 1_200,
            bufferProfile = prefs[Keys.BUFFER_PROFILE]?.let { name ->
                BufferProfile.entries.firstOrNull { it.name == name }
            } ?: BufferProfile.NORMAL,
            autoUpdate = prefs[Keys.AUTO_UPDATE] ?: true,
            userAgent = prefs[Keys.USER_AGENT]?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT,
            epgDaysAhead = prefs[Keys.EPG_DAYS] ?: 3,
            hideAdult = prefs[Keys.HIDE_ADULT] ?: false,
            externalPlayer = prefs[Keys.EXTERNAL_PLAYER] ?: "",
            verboseHttpLog = prefs[Keys.VERBOSE_HTTP] ?: false,
            autoSyncHours = prefs[Keys.AUTO_SYNC_HOURS] ?: 12
        )
    }

    suspend fun setStreamFormat(value: StreamFormat) = edit { it[Keys.STREAM_FORMAT] = value.name }
    suspend fun setPreviewEnabled(value: Boolean) = edit { it[Keys.PREVIEW_ENABLED] = value }
    suspend fun setBufferProfile(value: BufferProfile) = edit { it[Keys.BUFFER_PROFILE] = value.name }
    suspend fun setAutoUpdate(value: Boolean) = edit { it[Keys.AUTO_UPDATE] = value }
    suspend fun setUserAgent(value: String) = edit { it[Keys.USER_AGENT] = value }
    suspend fun setEpgDays(value: Int) = edit { it[Keys.EPG_DAYS] = value }
    suspend fun setHideAdult(value: Boolean) = edit { it[Keys.HIDE_ADULT] = value }
    suspend fun setExternalPlayer(value: String) = edit { it[Keys.EXTERNAL_PLAYER] = value }
    suspend fun setVerboseHttpLog(value: Boolean) = edit { it[Keys.VERBOSE_HTTP] = value }
    suspend fun setAutoSyncHours(value: Int) = edit { it[Keys.AUTO_SYNC_HOURS] = value }

    val lastUpdateCheck: Flow<Long> = context.dataStore.data.map { it[Keys.LAST_UPDATE_CHECK] ?: 0L }
    suspend fun setLastUpdateCheck(value: Long) = edit { it[Keys.LAST_UPDATE_CHECK] = value }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private inline fun <reified T> decode(raw: String?, fallback: T): T =
        if (raw.isNullOrBlank()) fallback
        else runCatching { json.decodeFromString<T>(raw) }
            .onFailure { Klog.w("Prefs", "No se pudo leer una preferencia guardada", it) }
            .getOrDefault(fallback)

    private companion object {
        const val MAX_HISTORY = 120
    }
}
