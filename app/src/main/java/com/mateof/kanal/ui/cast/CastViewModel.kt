package com.mateof.kanal.ui.cast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.kanal.R
import com.mateof.kanal.cast.CastDevice
import com.mateof.kanal.cast.UpnpClient
import com.mateof.kanal.core.UiText
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.repo.ContentRepository
import com.mateof.kanal.data.repo.PlaybackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What is being offered to the network, named rather than resolved up front. */
sealed interface CastTarget {
    data class Channel(val streamId: String) : CastTarget
    data class Movie(val movieId: String) : CastTarget
    data class Episode(val episodeId: String) : CastTarget
}

data class CastUiState(
    val target: CastTarget? = null,
    val title: String = "",
    val devices: List<CastDevice> = emptyList(),
    val searching: Boolean = false,
    val sentTo: String? = null,
    val error: String? = null,
    val hint: UiText? = null
) {
    val open: Boolean get() = target != null
}

/**
 * Sending from a list, before anything is playing here.
 *
 * This exists because of what most IPTV accounts allow: often a single
 * simultaneous connection. Opening a channel and then handing it to the
 * television means asking the provider for the same stream twice, and the
 * second request is refused. Sending it straight from the list never opens the
 * local connection at all.
 */
@HiltViewModel
class CastViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val content: ContentRepository,
    private val playback: PlaybackRepository,
    private val upnp: UpnpClient,
    private val logger: FileLogger
) : ViewModel() {

    private val _state = MutableStateFlow(CastUiState())
    val state: StateFlow<CastUiState> = _state.asStateFlow()

    /** Devices the user typed in; they never come back from a scan. */
    private var manual: List<CastDevice> = emptyList()

    fun open(target: CastTarget, title: String) {
        _state.value = CastUiState(target = target, title = title, devices = _state.value.devices)
        viewModelScope.launch {
            loadRemembered()
            search()
        }
    }

    /** Re-reads the saved addresses, so a television added once stays. */
    private suspend fun loadRemembered() {
        if (manual.isNotEmpty()) return
        val found = prefs.castAddresses.first().mapNotNull { upnp.describeManual(it) }
        if (found.isEmpty()) return
        manual = found
        _state.value = _state.value.copy(devices = merge(_state.value.devices))
    }

    /** Discovery must never drop what the user added by hand. */
    private fun merge(discovered: List<CastDevice>): List<CastDevice> =
        (discovered + manual).distinctBy { it.controlUrl }

    fun close() {
        _state.value = _state.value.copy(target = null, error = null, hint = null)
    }

    fun search() {
        if (_state.value.searching) return
        viewModelScope.launch {
            _state.value = _state.value.copy(searching = true, error = null, hint = null)
            val found = upnp.discover()
            _state.value = _state.value.copy(devices = merge(found), searching = false)
        }
    }

    fun addByAddress(address: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(searching = true, error = null, hint = null)
            val device = upnp.describeManual(address)
            if (device == null) {
                _state.value = _state.value.copy(
                    searching = false,
                    error = "No responde en esa dirección"
                )
            } else {
                manual = (manual + device).distinctBy { it.controlUrl }
                prefs.rememberCastAddress(address.trim())
                _state.value = _state.value.copy(
                    searching = false,
                    devices = merge(_state.value.devices)
                )
            }
        }
    }

    fun sendTo(device: CastDevice) {
        val target = _state.value.target ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(searching = true, error = null, hint = null)
            val playable = resolve(target)
            if (playable == null) {
                _state.value = _state.value.copy(searching = false, error = "No se encontró el contenido")
                return@launch
            }
            upnp.play(device, playable.first, playable.second)
                .onSuccess {
                    _state.value = _state.value.copy(searching = false, sentTo = device.name)
                }
                .onFailure { failure ->
                    logger.w("Cast", "No se pudo enviar a ${device.name}", failure)
                    val detail = failure.message ?: "Error desconocido"
                    _state.value = _state.value.copy(
                        searching = false,
                        error = detail,
                        hint = hintFor(detail)
                    )
                }
        }
    }

    fun stopSending() {
        val device = _state.value.devices.firstOrNull { it.name == _state.value.sentTo }
        viewModelScope.launch {
            device?.let { upnp.stop(it) }
            _state.value = _state.value.copy(sentTo = null)
        }
    }

    /** @return the url to hand over and the title to announce. */
    private suspend fun resolve(target: CastTarget): Pair<String, String>? {
        val source = prefs.activeSource.first() ?: return null
        val playable = when (target) {
            is CastTarget.Channel ->
                content.channel(source.id, target.streamId)?.let { playback.forChannel(source, it) }

            is CastTarget.Movie ->
                content.movie(source.id, target.movieId)?.let { playback.forMovie(source, it) }

            is CastTarget.Episode -> content.episode(source.id, target.episodeId)?.let { episode ->
                val series = content.seriesById(source.id, episode.seriesId)
                playback.forEpisode(source, episode, series?.name.orEmpty())
            }
        } ?: return null
        return playable.url to playable.title
    }

    /** A UPnP code is exact but says nothing about what to do about it. */
    private fun hintFor(detail: String): UiText? = when {
        detail.contains("UPnP 716") -> UiText(R.string.cast_hint_716)
        detail.contains("UPnP 714") -> UiText(R.string.cast_hint_714)
        detail.contains("UPnP 701") -> UiText(R.string.cast_hint_701)
        detail.contains("UPnP 402") -> UiText(R.string.cast_hint_402)
        detail.contains("UPnP 401") -> UiText(R.string.cast_hint_401)
        else -> null
    }
}
