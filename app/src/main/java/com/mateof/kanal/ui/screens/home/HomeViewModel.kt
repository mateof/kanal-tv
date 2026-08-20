package com.mateof.kanal.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.kanal.data.db.ChannelEntity
import com.mateof.kanal.data.db.MovieEntity
import com.mateof.kanal.data.db.SeriesEntity
import com.mateof.kanal.data.model.HistoryItem
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.repo.ContentRepository
import com.mateof.kanal.data.repo.EpgRepository
import com.mateof.kanal.data.repo.SyncRepository
import com.mateof.kanal.data.repo.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeState(
    val source: Source? = null,
    val channels: Int = 0,
    val movies: Int = 0,
    val series: Int = 0,
    val continueWatching: List<HistoryItem> = emptyList(),
    val recentChannels: List<ChannelEntity> = emptyList(),
    val favoriteChannels: List<ChannelEntity> = emptyList(),
    val newMovies: List<MovieEntity> = emptyList(),
    val newSeries: List<SeriesEntity> = emptyList(),
    val nowPlaying: Map<String, com.mateof.kanal.data.db.EpgEntity> = emptyMap(),
    val loading: Boolean = true
) {
    val isEmpty: Boolean get() = channels == 0 && movies == 0 && series == 0
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val content: ContentRepository,
    private val epg: EpgRepository,
    private val sync: SyncRepository
) : ViewModel() {

    private val activeSource = prefs.activeSource

    /** Every source configured, for switching without going into the settings. */
    val sources: StateFlow<List<Source>> = prefs.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun useSource(id: String) = viewModelScope.launch { prefs.setActiveSource(id) }

    val syncState: StateFlow<SyncState> = sync.state

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val state: StateFlow<HomeState> = activeSource.flatMapLatest { source ->
        if (source == null) {
            flowOf(HomeState(loading = false))
        } else {
            combine(
                content.counts(source.id),
                prefs.history,
                combine(
                    content.favoriteChannels(source.id),
                    content.recentMovies(source.id),
                    content.recentSeries(source.id)
                ) { favorites, movies, series -> Triple(favorites, movies, series) },
                epg.nowPlaying(source.id)
            ) { counts, history, extras, now ->
                val (favorites, movies, series) = extras
                HomeState(
                    source = source,
                    channels = counts.first,
                    movies = counts.second,
                    series = counts.third,
                    continueWatching = history
                        .filter { !it.isFinished && it.positionMs > 30_000 && it.sourceId == source.id }
                        .take(12),
                    recentChannels = emptyList(),
                    favoriteChannels = favorites.take(20),
                    newMovies = movies,
                    newSeries = series,
                    nowPlaying = now,
                    loading = false
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    /** Channels the user watched recently, resolved from the history entries. */
    val recentChannels: StateFlow<List<ChannelEntity>> = combine(
        activeSource.filterNotNull(),
        prefs.history
    ) { source, history ->
        source to history.filter { it.sourceId == source.id && it.kind.name == "LIVE" }.take(12)
    }.map { (source, entries) ->
        entries.mapNotNull { content.channel(source.id, it.itemId) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refresh() {
        viewModelScope.launch {
            val source = activeSource.first() ?: return@launch
            _refreshing.value = true
            sync.syncAll(source)
            _refreshing.value = false
        }
    }

    /** Refreshes in the background when the cache is older than the setting. */
    fun refreshIfStale() {
        viewModelScope.launch {
            val source = activeSource.first() ?: return@launch
            val hours = prefs.settings.first().autoSyncHours
            if (hours <= 0) return@launch
            val age = System.currentTimeMillis() - source.lastSyncAt
            if (source.lastSyncAt > 0 && age < hours * 3_600_000L) return@launch
            _refreshing.value = true
            sync.syncAll(source)
            _refreshing.value = false
        }
    }
}
