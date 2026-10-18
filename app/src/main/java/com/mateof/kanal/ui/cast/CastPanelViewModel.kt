package com.mateof.kanal.ui.cast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.kanal.cast.CastController
import com.mateof.kanal.cast.CastMonitor
import com.mateof.kanal.cast.CastSession
import com.mateof.kanal.data.db.EpgEntity
import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.data.repo.EpgRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The transmission panel: what the monitor gathered, and the few things to do about it. */
@HiltViewModel
class CastPanelViewModel @Inject constructor(
    private val cast: CastController,
    private val monitor: CastMonitor,
    private val epg: EpgRepository
) : ViewModel() {

    val session: StateFlow<CastSession?> = cast.session

    private val _programme = MutableStateFlow<EpgEntity?>(null)

    /** What is on air on the channel being sent, when the guide knows. */
    val programme: StateFlow<EpgEntity?> = _programme.asStateFlow()

    init {
        viewModelScope.launch {
            session.map { it?.request?.origin }.distinctUntilChanged().collectLatest { origin ->
                if (origin == null || origin.kind != ContentKind.LIVE || origin.epgChannelId.isBlank()) {
                    _programme.value = null
                    return@collectLatest
                }
                while (true) {
                    _programme.value = epg.nowNext(origin.sourceId, origin.epgChannelId).now
                    delay(PROGRAMME_REFRESH_MS)
                }
            }
        }
    }

    /** The television is asked what it is doing only while somebody is looking. */
    fun visible(on: Boolean) = monitor.watching(on)

    fun stop() = cast.stopInBackground()

    fun reconnect() = cast.reconnectInBackground()

    fun dismiss() = cast.dismiss()

    fun togglePause() {
        viewModelScope.launch { cast.togglePause() }
    }

    fun seekTo(positionMs: Long) {
        viewModelScope.launch { cast.seek(positionMs.coerceAtLeast(0)) }
    }

    /** From where the television last said it was; nothing to go by, nothing to do. */
    fun seekBy(deltaMs: Long) {
        val position = session.value?.position ?: return
        val elapsed = position.elapsedMs ?: 0L
        val end = position.durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE
        seekTo((elapsed + deltaMs).coerceAtMost(end))
    }

    /**
     * Stops the television, then hands over. The pause matters for the same
     * reason as when sending: with a single-connection account the phone is
     * refused until the provider notices the television has gone.
     */
    fun watchHere(then: () -> Unit) {
        viewModelScope.launch {
            cast.stopCurrent()
            delay(SLOT_RELEASE_MS)
            then()
        }
    }

    private companion object {
        const val PROGRAMME_REFRESH_MS = 60_000L
        const val SLOT_RELEASE_MS = 1_500L
    }
}
