package com.mateof.kanal.ui.screens.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.db.CategoryEntity
import com.mateof.kanal.data.db.ChannelEntity
import com.mateof.kanal.data.db.EpgEntity
import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.model.favoriteKey
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.prefs.Settings
import com.mateof.kanal.data.repo.ContentRepository
import com.mateof.kanal.data.repo.EpgRepository
import com.mateof.kanal.data.repo.PlaybackRepository
import com.mateof.kanal.player.PlayerFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Pseudo-categories that sit above the provider's own list. */
const val CATEGORY_ALL = ""
const val CATEGORY_FAVORITES = "__favorites__"

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LiveViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val content: ContentRepository,
    private val epg: EpgRepository,
    private val playback: PlaybackRepository,
    private val playerFactory: PlayerFactory,
    private val logger: FileLogger
) : ViewModel() {

    private val activeSource = prefs.activeSource

    private val _selectedCategory = MutableStateFlow(CATEGORY_ALL)
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _focused = MutableStateFlow<ChannelEntity?>(null)
    val focused: StateFlow<ChannelEntity?> = _focused.asStateFlow()

    private val _now = MutableStateFlow<EpgEntity?>(null)
    val now: StateFlow<EpgEntity?> = _now.asStateFlow()

    private val _next = MutableStateFlow<EpgEntity?>(null)
    val next: StateFlow<EpgEntity?> = _next.asStateFlow()

    private val _schedule = MutableStateFlow<List<EpgEntity>>(emptyList())
    val schedule: StateFlow<List<EpgEntity>> = _schedule.asStateFlow()

    private val _guideDays = MutableStateFlow<List<Long>>(emptyList())
    val guideDays: StateFlow<List<Long>> = _guideDays.asStateFlow()

    private val _selectedDay = MutableStateFlow(0L)
    val selectedDay: StateFlow<Long> = _selectedDay.asStateFlow()

    private val _previewActive = MutableStateFlow(false)
    val previewActive: StateFlow<Boolean> = _previewActive.asStateFlow()

    private val _previewError = MutableStateFlow("")
    val previewError: StateFlow<String> = _previewError.asStateFlow()

    private var previewJob: Job? = null
    private var player: ExoPlayer? = null

    /** Same container fallback the full player does, so a preview that fails
     *  does not mislabel a channel that plays fine once opened. */
    private var previewCandidates: List<String> = emptyList()
    private var previewIndex = 0
    private var previewTitle = ""

    val source: StateFlow<Source?> =
        activeSource.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<Settings> =
        prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    val favorites: StateFlow<Set<String>> =
        prefs.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val categories: StateFlow<List<CategoryEntity>> = activeSource.flatMapLatest { source ->
        if (source == null) flowOf(emptyList()) else content.categories(source.id, ContentKind.LIVE)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val channels: Flow<PagingData<ChannelEntity>> =
        combine(activeSource, _selectedCategory) { source, category -> source to category }
            .flatMapLatest { (source, category) ->
                when {
                    source == null -> flowOf(PagingData.empty())
                    category == CATEGORY_FAVORITES -> flowOf(PagingData.empty())
                    else -> content.channels(source.id, category, "")
                }
            }
            .cachedIn(viewModelScope)

    val favoriteChannels: StateFlow<List<ChannelEntity>> = activeSource.flatMapLatest { source ->
        if (source == null) flowOf(emptyList()) else content.favoriteChannels(source.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val nowPlaying: StateFlow<Map<String, EpgEntity>> = activeSource.flatMapLatest { source ->
        if (source == null) flowOf(emptyMap()) else epg.nowPlaying(source.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun selectCategory(id: String) {
        if (_selectedCategory.value == id) return
        _selectedCategory.value = id
        _focused.value = null
        stopPreview()
    }

    fun onChannelFocused(channel: ChannelEntity) {
        if (_focused.value?.streamId == channel.streamId) return
        _focused.value = channel
        loadGuideFor(channel)
        schedulePreview(channel)
    }

    private fun loadGuideFor(channel: ChannelEntity) {
        viewModelScope.launch {
            val current = activeSource.first() ?: return@launch
            var channelId = channel.epgChannelId
            var nowNext = epg.nowNext(current.id, channelId)
            if (nowNext.now == null && nowNext.next == null) {
                // Nothing in the XMLTV for this channel: ask the panel itself.
                val fallback = epg.fetchShortEpg(current, channel.streamId, channel.epgChannelId)
                if (fallback.isNotEmpty()) {
                    channelId = fallback.first().channelId
                    nowNext = epg.nowNext(current.id, channelId)
                }
            }
            if (_focused.value?.streamId != channel.streamId) return@launch
            _now.value = nowNext.now
            _next.value = nowNext.next

            val days = epg.availableDays(current.id, channelId)
            val today = days.firstOrNull() ?: 0L
            _guideDays.value = days
            _selectedDay.value = today
            _schedule.value = if (today > 0) {
                epg.programmesOfDay(current.id, channelId, today)
            } else {
                emptyList()
            }
        }
    }

    fun selectDay(day: Long) {
        val channel = _focused.value ?: return
        viewModelScope.launch {
            val current = activeSource.first() ?: return@launch
            val channelId = channel.epgChannelId.ifBlank { "stream:${channel.streamId}" }
            _selectedDay.value = day
            _schedule.value = epg.programmesOfDay(current.id, channelId, day)
        }
    }

    /**
     * Starts a muted-free preview only after the focus has settled: zapping
     * through a list must not fire one connection per channel.
     */
    private fun schedulePreview(channel: ChannelEntity) {
        previewJob?.cancel()
        _previewError.value = ""
        player?.stop()
        _previewActive.value = false

        previewJob = viewModelScope.launch {
            val config = prefs.settings.first()
            if (!config.previewEnabled) return@launch
            delay(config.previewDelayMs.toLong())
            val current = activeSource.first() ?: return@launch
            if (_focused.value?.streamId != channel.streamId) return@launch

            val playable = playback.forChannel(current, channel)
            previewCandidates = listOf(playable.url) + playable.fallbackUrls
            previewIndex = 0
            previewTitle = playable.title

            val exo = ensurePlayer(playable.userAgent, config)
            exo.setMediaItem(playerFactory.mediaItem(playable.url, playable.title))
            exo.prepare()
            exo.playWhenReady = true
            _previewActive.value = true
        }
    }

    private fun ensurePlayer(userAgent: String, config: Settings): ExoPlayer {
        player?.let { return it }
        val created = playerFactory.create(userAgent, config.bufferProfile)
        created.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                logger.w("Preview", "Vista previa fallida: ${error.errorCodeName}")
                if (advancePreview()) return
                _previewError.value = "No se pudo cargar la vista previa"
                _previewActive.value = false
            }
        })
        player = created
        return created
    }

    /** @return true when another container variant was queued. */
    private fun advancePreview(): Boolean {
        if (previewIndex + 1 >= previewCandidates.size) return false
        val exo = player ?: return false
        previewIndex++
        exo.setMediaItem(playerFactory.mediaItem(previewCandidates[previewIndex], previewTitle))
        exo.prepare()
        exo.playWhenReady = true
        return true
    }

    fun previewPlayer(): ExoPlayer? = player

    fun stopPreview() {
        previewJob?.cancel()
        player?.stop()
        _previewActive.value = false
    }

    fun toggleFavorite(channel: ChannelEntity) {
        viewModelScope.launch {
            val current = activeSource.first() ?: return@launch
            prefs.toggleFavorite(ContentKind.LIVE, current.id, channel.streamId)
        }
    }

    fun isFavorite(sourceId: String, streamId: String): Boolean =
        favorites.value.contains(favoriteKey(ContentKind.LIVE, sourceId, streamId))

    override fun onCleared() {
        previewJob?.cancel()
        player?.release()
        player = null
        super.onCleared()
    }
}
