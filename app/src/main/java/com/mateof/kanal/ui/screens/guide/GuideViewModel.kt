package com.mateof.kanal.ui.screens.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.kanal.data.db.CategoryEntity
import com.mateof.kanal.data.db.ChannelEntity
import com.mateof.kanal.data.db.EpgEntity
import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.repo.ContentRepository
import com.mateof.kanal.data.repo.EpgRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/** One channel row of the wall, already resolved to its programmes. */
data class GuideRow(
    val channel: ChannelEntity,
    val programmes: List<EpgEntity>
)

data class GuideState(
    val rows: List<GuideRow> = emptyList(),
    val windowStart: Long = 0L,
    val windowEnd: Long = 0L,
    val loading: Boolean = true,
    val hasGuide: Boolean = true,
    val truncated: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GuideViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val content: ContentRepository,
    private val epg: EpgRepository
) : ViewModel() {

    private val activeSource = prefs.activeSource

    private val _category = MutableStateFlow("")
    val category: StateFlow<String> = _category.asStateFlow()

    private val _windowStart = MutableStateFlow(floorToHalfHour(System.currentTimeMillis()))
    val windowStart: StateFlow<Long> = _windowStart.asStateFlow()

    private val _state = MutableStateFlow(GuideState())
    val state: StateFlow<GuideState> = _state.asStateFlow()

    private val _selected = MutableStateFlow<Pair<ChannelEntity, EpgEntity>?>(null)
    val selected: StateFlow<Pair<ChannelEntity, EpgEntity>?> = _selected.asStateFlow()

    val categories: StateFlow<List<CategoryEntity>> = activeSource.flatMapLatest { source ->
        if (source == null) flowOf(emptyList()) else content.categories(source.id, ContentKind.LIVE)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var loadJob: Job? = null

    init {
        reload()
    }

    fun selectCategory(id: String) {
        if (_category.value == id) return
        _category.value = id
        _selected.value = null
        reload()
    }

    /** Moves the visible window; the wall itself scrolls within it. */
    fun shiftWindow(hours: Int) {
        val next = _windowStart.value + hours * HOUR
        // Never walk back past what the guide keeps (12 h of history).
        val floor = floorToHalfHour(System.currentTimeMillis()) - 12 * HOUR
        _windowStart.value = next.coerceAtLeast(floor)
        reload()
    }

    fun resetToNow() {
        _windowStart.value = floorToHalfHour(System.currentTimeMillis())
        reload()
    }

    fun onProgrammeFocused(channel: ChannelEntity, programme: EpgEntity) {
        _selected.value = channel to programme
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val source: Source? = activeSource.first()
            if (source == null) {
                _state.value = GuideState(loading = false, hasGuide = false)
                return@launch
            }

            val channels = content.channelList(source.id, _category.value, CHANNEL_LIMIT)
            val from = _windowStart.value
            val to = from + WINDOW_HOURS * HOUR
            val byChannel = epg.wall(source.id, channels.map { it.epgChannelId }, from, to)

            _state.value = GuideState(
                rows = channels.map { channel ->
                    GuideRow(channel, byChannel[channel.epgChannelId].orEmpty())
                },
                windowStart = from,
                windowEnd = to,
                loading = false,
                hasGuide = byChannel.isNotEmpty(),
                truncated = channels.size >= CHANNEL_LIMIT
            )
        }
    }

    companion object {
        const val HOUR = 3_600_000L
        const val WINDOW_HOURS = 6
        const val CHANNEL_LIMIT = 150

        /** The ruler reads better starting on a whole or half hour. */
        fun floorToHalfHour(millis: Long): Long {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = millis
                set(Calendar.MINUTE, if (get(Calendar.MINUTE) >= 30) 30 else 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return calendar.timeInMillis
        }
    }
}
