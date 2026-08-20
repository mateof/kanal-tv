package com.mateof.kanal.data.prefs

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mateof.kanal.R
import com.mateof.kanal.core.AppLanguage
import com.mateof.kanal.core.SleepTimer
import com.mateof.kanal.core.log.Klog
import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.data.model.HistoryItem
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.reminders.Reminder
import com.mateof.kanal.data.model.favoriteKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kanal")

enum class StreamFormat(@StringRes val labelRes: Int, val extension: String) {
    TS(R.string.stream_format_ts, "ts"),
    HLS(R.string.stream_format_hls, "m3u8")
}

/** The order the channel list is shown in. */
enum class ChannelSort(@StringRes val labelRes: Int, val key: String) {
    PROVIDER(R.string.sort_provider, "provider"),
    NUMBER(R.string.sort_number, "number"),
    NAME(R.string.sort_name, "name")
}

/** How big the subtitles are, from a sofa away. */
enum class SubtitleSize(@StringRes val labelRes: Int, val sp: Float) {
    SMALL(R.string.subtitle_size_small, 16f),
    NORMAL(R.string.subtitle_size_normal, 22f),
    LARGE(R.string.subtitle_size_large, 30f),
    HUGE(R.string.subtitle_size_huge, 40f)
}

/** How they are drawn, which matters more over a bright picture than size does. */
enum class SubtitleLook(@StringRes val labelRes: Int) {
    PLAIN(R.string.subtitle_look_plain),
    OUTLINED(R.string.subtitle_look_outlined),
    BOXED(R.string.subtitle_look_boxed),
    YELLOW(R.string.subtitle_look_yellow)
}

enum class BufferProfile(
    @StringRes val labelRes: Int,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val startMs: Int
) {
    LOW(R.string.buffer_low, 1_500, 15_000, 800),
    NORMAL(R.string.buffer_normal, 5_000, 30_000, 1_500),
    HIGH(R.string.buffer_high, 15_000, 60_000, 3_000),
    MAXIMUM(R.string.buffer_maximum, 45_000, 180_000, 5_000)
}

data class Settings(
    val language: AppLanguage = AppLanguage.AUTO,
    val streamFormat: StreamFormat = StreamFormat.TS,
    val previewEnabled: Boolean = true,
    val keepLastChannel: Boolean = true,
    val resilientPlayback: Boolean = true,
    val previewDelayMs: Int = 1_200,
    val bufferProfile: BufferProfile = BufferProfile.NORMAL,
    val autoUpdate: Boolean = true,
    val userAgent: String = DEFAULT_USER_AGENT,
    val epgDaysAhead: Int = 3,
    val hideAdult: Boolean = false,
    val externalPlayer: String = "",
    val verboseHttpLog: Boolean = false,
    val autoSyncHours: Int = 12,
    val sleepTimerMinutes: Int = SleepTimer.DEFAULT_MINUTES,
    val stillWatching: Boolean = true,
    val subtitlesEnabled: Boolean = false,
    val fillMissingLogos: Boolean = true,
    val subtitleSize: SubtitleSize = SubtitleSize.NORMAL,
    val subtitleLook: SubtitleLook = SubtitleLook.OUTLINED,
    val channelSort: ChannelSort = ChannelSort.PROVIDER
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
        val STREAM_CHOICES = stringPreferencesKey("stream_choices")
        val FILL_LOGOS = booleanPreferencesKey("fill_logos")
        val PARENTAL_PIN = stringPreferencesKey("parental_pin")
        val CATALOG_MARKS = stringPreferencesKey("catalog_marks")
        val REMINDERS = stringPreferencesKey("reminders")
        val CHANNEL_SORT = stringPreferencesKey("channel_sort")
        val HIDDEN_CHANNELS = stringPreferencesKey("hidden_channels")
        val HIDDEN_CATEGORIES = stringPreferencesKey("hidden_categories")
        val SUBTITLE_SIZE = stringPreferencesKey("subtitle_size")
        val SUBTITLE_LOOK = stringPreferencesKey("subtitle_look")
        val HISTORY = stringPreferencesKey("history")

        val STREAM_FORMAT = stringPreferencesKey("stream_format")
        val PREVIEW_ENABLED = booleanPreferencesKey("preview_enabled")
        val KEEP_LAST_CHANNEL = booleanPreferencesKey("keep_last_channel")
        val RESILIENT = booleanPreferencesKey("resilient_playback")
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
        val LANGUAGE = stringPreferencesKey("language")
        val SLEEP_MINUTES = intPreferencesKey("sleep_minutes")
        val STILL_WATCHING = booleanPreferencesKey("still_watching")
        val SUBTITLES = booleanPreferencesKey("subtitles_enabled")
        val CAST_ADDRESSES = stringPreferencesKey("cast_addresses")
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

    // --- Which stream format each channel answers to -------------------------

    /** Channel key to the id of the url that last produced a picture. */
    val streamChoices: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        decode<Map<String, String>>(prefs[Keys.STREAM_CHOICES], emptyMap())
    }

    /**
     * Written only when the answer changes, which after the first pass through
     * the list is almost never: a channel is opened far more often than it
     * changes the format it answers in, and the whole map is rewritten on every
     * edit.
     */
    suspend fun rememberStreamChoice(key: String, choice: String) {
        val current = streamChoices.first()
        if (current[key] == choice) return
        context.dataStore.edit { prefs ->
            val all = decode<Map<String, String>>(prefs[Keys.STREAM_CHOICES], emptyMap())
                .toMutableMap()
            all[key] = choice
            prefs[Keys.STREAM_CHOICES] = json.encodeToString(all.toMap())
        }
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
            language = AppLanguage.of(prefs[Keys.LANGUAGE]),
            streamFormat = prefs[Keys.STREAM_FORMAT]?.let { name ->
                StreamFormat.entries.firstOrNull { it.name == name }
            } ?: StreamFormat.TS,
            previewEnabled = prefs[Keys.PREVIEW_ENABLED] ?: true,
            keepLastChannel = prefs[Keys.KEEP_LAST_CHANNEL] ?: true,
            resilientPlayback = prefs[Keys.RESILIENT] ?: true,
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
            autoSyncHours = prefs[Keys.AUTO_SYNC_HOURS] ?: 12,
            sleepTimerMinutes = prefs[Keys.SLEEP_MINUTES] ?: SleepTimer.DEFAULT_MINUTES,
            stillWatching = prefs[Keys.STILL_WATCHING] ?: true,
            subtitlesEnabled = prefs[Keys.SUBTITLES] ?: false,
            fillMissingLogos = prefs[Keys.FILL_LOGOS] ?: true,
            subtitleSize = prefs[Keys.SUBTITLE_SIZE]?.let { name ->
                SubtitleSize.entries.firstOrNull { it.name == name }
            } ?: SubtitleSize.NORMAL,
            subtitleLook = prefs[Keys.SUBTITLE_LOOK]?.let { name ->
                SubtitleLook.entries.firstOrNull { it.name == name }
            } ?: SubtitleLook.OUTLINED,
            channelSort = prefs[Keys.CHANNEL_SORT]?.let { name ->
                ChannelSort.entries.firstOrNull { it.name == name }
            } ?: ChannelSort.PROVIDER
        )
    }

    /**
     * Read on its own so the interface can pick a language up before the rest of
     * the settings are needed, and re-render the moment it changes.
     */
    val language: Flow<AppLanguage> = context.dataStore.data.map { AppLanguage.of(it[Keys.LANGUAGE]) }

    suspend fun setStreamFormat(value: StreamFormat) = edit { it[Keys.STREAM_FORMAT] = value.name }
    suspend fun setPreviewEnabled(value: Boolean) = edit { it[Keys.PREVIEW_ENABLED] = value }
    suspend fun setKeepLastChannel(value: Boolean) = edit { it[Keys.KEEP_LAST_CHANNEL] = value }
    suspend fun setResilientPlayback(value: Boolean) = edit { it[Keys.RESILIENT] = value }
    suspend fun setBufferProfile(value: BufferProfile) = edit { it[Keys.BUFFER_PROFILE] = value.name }
    suspend fun setAutoUpdate(value: Boolean) = edit { it[Keys.AUTO_UPDATE] = value }
    suspend fun setUserAgent(value: String) = edit { it[Keys.USER_AGENT] = value }
    suspend fun setEpgDays(value: Int) = edit { it[Keys.EPG_DAYS] = value }
    suspend fun setHideAdult(value: Boolean) = edit { it[Keys.HIDE_ADULT] = value }
    suspend fun setExternalPlayer(value: String) = edit { it[Keys.EXTERNAL_PLAYER] = value }
    suspend fun setVerboseHttpLog(value: Boolean) = edit { it[Keys.VERBOSE_HTTP] = value }
    suspend fun setAutoSyncHours(value: Int) = edit { it[Keys.AUTO_SYNC_HOURS] = value }
    suspend fun setLanguage(value: AppLanguage) = edit { it[Keys.LANGUAGE] = value.name }
    suspend fun setSleepTimerMinutes(value: Int) = edit { it[Keys.SLEEP_MINUTES] = value }
    suspend fun setStillWatching(value: Boolean) = edit { it[Keys.STILL_WATCHING] = value }
    suspend fun setSubtitlesEnabled(value: Boolean) = edit { it[Keys.SUBTITLES] = value }

    suspend fun setFillMissingLogos(value: Boolean) = edit { it[Keys.FILL_LOGOS] = value }

    // --- What each catalogue looked like last time ----------------------------
    //
    // A panel has no way of saying "nothing changed", so the app works it out:
    // it keeps a fingerprint of what came back and, when the new one matches,
    // leaves the stored rows alone. The fetch still happens — there is no
    // avoiding that — but rewriting tens of thousands of rows does not.

    val catalogMarks: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        decode<Map<String, String>>(prefs[Keys.CATALOG_MARKS], emptyMap())
    }

    suspend fun markCatalog(key: String, fingerprint: String) {
        context.dataStore.edit { prefs ->
            val all = decode<Map<String, String>>(prefs[Keys.CATALOG_MARKS], emptyMap())
                .toMutableMap()
            all[key] = fingerprint
            prefs[Keys.CATALOG_MARKS] = json.encodeToString(all.toMap())
        }
    }

    suspend fun forgetCatalogMarks() = edit { it.remove(Keys.CATALOG_MARKS) }

    // --- Programme reminders --------------------------------------------------

    val reminders: Flow<List<Reminder>> = context.dataStore.data.map { prefs ->
        decode<List<Reminder>>(prefs[Keys.REMINDERS], emptyList())
            .sortedBy { it.startMillis }
    }

    suspend fun addReminder(reminder: Reminder) = editReminders { all ->
        all.removeAll { it.id == reminder.id }
        all += reminder
    }

    suspend fun removeReminder(reminder: Reminder) = editReminders { all ->
        all.removeAll { it.id == reminder.id }
    }

    private suspend fun editReminders(change: (MutableList<Reminder>) -> Unit) {
        context.dataStore.edit { prefs ->
            val all = decode<List<Reminder>>(prefs[Keys.REMINDERS], emptyList()).toMutableList()
            change(all)
            // Yesterday's reminders are of no use to anybody and would pile up.
            val cutoff = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
            prefs[Keys.REMINDERS] =
                json.encodeToString(all.filter { it.startMillis > cutoff })
        }
    }

    // --- Parental pin ---------------------------------------------------------
    //
    // Kept as a digest and never as the digits themselves. It guards nothing
    // valuable, but a four-digit code sitting in plain text in the preferences
    // is the sort of thing that ends up being the same four digits as something
    // that does matter.

    val hasParentalPin: Flow<Boolean> = context.dataStore.data.map {
        !it[Keys.PARENTAL_PIN].isNullOrBlank()
    }

    suspend fun setParentalPin(pin: String?) = edit { prefs ->
        if (pin.isNullOrBlank()) prefs.remove(Keys.PARENTAL_PIN)
        else prefs[Keys.PARENTAL_PIN] = digestOf(pin)
    }

    /** @return true when there is no pin set, or when [pin] is the right one. */
    suspend fun parentalPinAccepts(pin: String): Boolean {
        val stored = context.dataStore.data.first()[Keys.PARENTAL_PIN]
        return stored.isNullOrBlank() || stored == digestOf(pin)
    }

    private fun digestOf(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(pin.trim().toByteArray())
            .joinToString("") { "%02x".format(it) }

    suspend fun setChannelSort(value: ChannelSort) = edit { it[Keys.CHANNEL_SORT] = value.name }

    // --- What the user does not want to see -----------------------------------

    val hiddenChannels: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        decode<List<String>>(prefs[Keys.HIDDEN_CHANNELS], emptyList()).toSet()
    }

    val hiddenCategories: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        decode<List<String>>(prefs[Keys.HIDDEN_CATEGORIES], emptyList()).toSet()
    }

    suspend fun toggleHiddenChannel(sourceId: String, streamId: String) =
        toggleIn(Keys.HIDDEN_CHANNELS, "$sourceId:$streamId")

    suspend fun toggleHiddenCategory(sourceId: String, categoryId: String) =
        toggleIn(Keys.HIDDEN_CATEGORIES, "$sourceId:$categoryId")

    suspend fun showEverything() = edit {
        it[Keys.HIDDEN_CHANNELS] = json.encodeToString(emptyList<String>())
        it[Keys.HIDDEN_CATEGORIES] = json.encodeToString(emptyList<String>())
    }

    private suspend fun toggleIn(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { prefs ->
            val all = decode<List<String>>(prefs[key], emptyList()).toMutableSet()
            if (!all.add(value)) all.remove(value)
            prefs[key] = json.encodeToString(all.toList())
        }
    }

    suspend fun setSubtitleSize(value: SubtitleSize) = edit { it[Keys.SUBTITLE_SIZE] = value.name }

    suspend fun setSubtitleLook(value: SubtitleLook) = edit { it[Keys.SUBTITLE_LOOK] = value.name }

    /**
     * Televisions added by hand, kept between sessions: the ones that need it
     * are exactly the ones discovery never finds, so asking again every time
     * would be asking forever.
     */
    val castAddresses: Flow<List<String>> = context.dataStore.data.map { prefs ->
        decode(prefs[Keys.CAST_ADDRESSES], emptyList())
    }

    suspend fun rememberCastAddress(address: String) {
        context.dataStore.edit { prefs ->
            val all = decode<List<String>>(prefs[Keys.CAST_ADDRESSES], emptyList()).toMutableList()
            if (!all.contains(address)) all += address
            prefs[Keys.CAST_ADDRESSES] = json.encodeToString(all)
        }
    }

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
