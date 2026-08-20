package com.mateof.kanal.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.kanal.R
import com.mateof.kanal.core.AppLanguage
import com.mateof.kanal.core.Plural
import com.mateof.kanal.core.SleepTimer
import com.mateof.kanal.core.UiText
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.db.KanalDatabase
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.prefs.SubtitleLook
import com.mateof.kanal.data.prefs.SubtitleSize
import com.mateof.kanal.data.repo.AccountRepository
import com.mateof.kanal.data.repo.AccountStatus
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.prefs.BufferProfile
import com.mateof.kanal.data.prefs.Settings
import com.mateof.kanal.data.prefs.StreamFormat
import com.mateof.kanal.data.repo.SyncRepository
import com.mateof.kanal.data.repo.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val sync: SyncRepository,
    private val db: KanalDatabase,
    private val logger: FileLogger,
    private val sleepTimer: SleepTimer,
    private val accounts: AccountRepository
) : ViewModel() {

    val settings: StateFlow<Settings> =
        prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    val sources: StateFlow<List<Source>> =
        prefs.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeSourceId: StateFlow<String?> =
        prefs.activeSourceId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val syncState: StateFlow<SyncState> = sync.state

    val sleepRemaining: StateFlow<Long?> = sleepTimer.remainingMs

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    // Named rather than written out: the screen turns it into words in whichever
    // language the interface settled on.
    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message.asStateFlow()

    fun setActive(id: String) = viewModelScope.launch { prefs.setActiveSource(id) }

    fun deleteSource(source: Source) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            db.channels().clear(source.id)
            db.movies().clear(source.id)
            db.series().clear(source.id)
            db.episodes().clear(source.id)
            db.epg().clear(source.id)
        }
        prefs.deleteSource(source.id)
        logger.i("Settings", "Fuente '${source.name}' eliminada")
        _message.value = UiText(R.string.msg_source_deleted, source.name)
    }

    fun syncNow(epgOnly: Boolean = false) = viewModelScope.launch {
        val source = prefs.activeSource.first() ?: return@launch
        _busy.value = true
        _message.value = null
        if (epgOnly) {
            val count = runCatching { sync.syncEpg(source) }.getOrElse { -1 }
            _message.value = if (count >= 0) {
                UiText(R.string.msg_guide_updated, listOf(Plural(R.plurals.count_programmes, count)))
            } else {
                UiText(R.string.msg_guide_failed)
            }
        } else {
            when (val result = sync.syncAll(source)) {
                is SyncState.Failed -> _message.value = result.message
                is SyncState.Done -> _message.value = with(result.summary) {
                    UiText(
                        R.string.msg_sync_done,
                        listOf(
                            Plural(R.plurals.count_channels, channels),
                            Plural(R.plurals.count_movies, movies),
                            Plural(R.plurals.count_series, series),
                            Plural(R.plurals.count_programmes, programmes)
                        )
                    )
                }

                else -> Unit
            }
        }
        _busy.value = false
    }

    fun clearCache() = viewModelScope.launch {
        _busy.value = true
        withContext(Dispatchers.IO) { db.clearAllTables() }
        _message.value = UiText(R.string.msg_cache_cleared)
        _busy.value = false
    }

    fun clearHistory() = viewModelScope.launch {
        prefs.clearHistory()
        _message.value = UiText(R.string.msg_history_cleared)
    }

    fun setLanguage(value: AppLanguage) = viewModelScope.launch { prefs.setLanguage(value) }
    fun setStreamFormat(value: StreamFormat) = viewModelScope.launch { prefs.setStreamFormat(value) }
    fun setBufferProfile(value: BufferProfile) = viewModelScope.launch { prefs.setBufferProfile(value) }
    fun setPreview(value: Boolean) = viewModelScope.launch { prefs.setPreviewEnabled(value) }
    fun setKeepLastChannel(value: Boolean) = viewModelScope.launch { prefs.setKeepLastChannel(value) }
    fun setResilient(value: Boolean) = viewModelScope.launch { prefs.setResilientPlayback(value) }
    fun setAutoUpdate(value: Boolean) = viewModelScope.launch { prefs.setAutoUpdate(value) }
    fun setHideAdult(value: Boolean) = viewModelScope.launch { prefs.setHideAdult(value) }
    fun setVerboseHttp(value: Boolean) = viewModelScope.launch { prefs.setVerboseHttpLog(value) }
    fun setUserAgent(value: String) = viewModelScope.launch { prefs.setUserAgent(value) }
    fun setEpgDays(value: Int) = viewModelScope.launch { prefs.setEpgDays(value.coerceIn(1, 14)) }
    fun setAutoSyncHours(value: Int) = viewModelScope.launch { prefs.setAutoSyncHours(value.coerceIn(0, 168)) }
    fun setStillWatching(value: Boolean) = viewModelScope.launch { prefs.setStillWatching(value) }

    fun setSleepMinutes(value: Int) = viewModelScope.launch {
        prefs.setSleepTimerMinutes(value.coerceIn(SleepTimer.MIN_MINUTES, SleepTimer.MAX_MINUTES))
    }

    fun startSleepTimer() = viewModelScope.launch {
        sleepTimer.start(prefs.settings.first().sleepTimerMinutes)
    }

    fun cancelSleepTimer() = sleepTimer.cancel()

    /** What the panel last said about the account's connections. */
    val account: StateFlow<AccountStatus?> = accounts.status

    fun checkAccount() = viewModelScope.launch {
        val source = prefs.activeSource.first() ?: return@launch
        accounts.refresh(source)
    }

    fun setSubtitleSize(value: SubtitleSize) = viewModelScope.launch { prefs.setSubtitleSize(value) }

    fun setSubtitleLook(value: SubtitleLook) = viewModelScope.launch { prefs.setSubtitleLook(value) }

    fun setFillMissingLogos(value: Boolean) = viewModelScope.launch {
        prefs.setFillMissingLogos(value)
    }
}
