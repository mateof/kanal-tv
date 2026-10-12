package com.mateof.kanal.ui.cast

import com.mateof.kanal.data.prefs.CastRouteMode
import com.mateof.kanal.cast.DirectRefused
import com.mateof.kanal.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.kanal.cast.CastController
import com.mateof.kanal.cast.CastDevice
import com.mateof.kanal.cast.CastHints
import com.mateof.kanal.cast.CastRequest
import com.mateof.kanal.cast.UpnpClient
import com.mateof.kanal.core.UiText
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.repo.ContentRepository
import com.mateof.kanal.data.repo.Playable
import com.mateof.kanal.data.repo.PlaybackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.mateof.kanal.link.KanalPeer
import com.mateof.kanal.link.LinkClient
import kotlinx.coroutines.async
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
    /** Other devices with Kanal open on the network. */
    val peers: List<KanalPeer> = emptyList(),
    val searching: Boolean = false,
    val sentTo: String? = null,
    /** Whether the stream goes through this phone rather than straight from the server. */
    val viaPhone: Boolean = false,
    val error: String? = null,
    val hint: UiText? = null,
    /** Directo failed in a way the relay gets past: offer it. */
    val canTryRelay: Boolean = false,
    /** Something is (or was, until it stopped by itself) on another device: the panel has it. */
    val transmission: Boolean = false
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
    private val cast: CastController,
    private val link: LinkClient,
    private val logger: FileLogger
) : ViewModel() {

    private val _state = MutableStateFlow(CastUiState())
    val state: StateFlow<CastUiState> = _state.asStateFlow()

    /** Devices the user typed in; they never come back from a scan. */
    private var manual: List<CastDevice> = emptyList()

    /** The device last sent to, so a relay that ends can be matched to it. */
    private var sentDevice: CastDevice? = null

    /** Kept after it stops, so a resend from the panel can be matched to it. */
    private var lastDevice: CastDevice? = null

    /** The device a send just failed on, for "try through the phone". */
    private var failedDevice: CastDevice? = null

    /** Another Kanal the item was sent to, so "bring it back" can stop it there. */
    private var sentPeer: KanalPeer? = null

    init {
        viewModelScope.launch {
            cast.session.collect { session ->
                _state.value = _state.value.copy(transmission = session != null)
            }
        }
        // Sent again from the transmission panel after it had stopped.
        viewModelScope.launch {
            cast.resumed.collect { resumed ->
                if (resumed.device.controlUrl != lastDevice?.controlUrl) return@collect
                sentDevice = resumed.device
                _state.value = _state.value.copy(
                    sentTo = resumed.device.name,
                    viaPhone = resumed.outcome.viaPhone,
                    error = null,
                    hint = null
                )
            }
        }
        viewModelScope.launch {
            cast.ended.collect { ended ->
                if (ended.device.controlUrl != sentDevice?.controlUrl) return@collect
                sentDevice = null
                _state.value = _state.value.copy(
                    sentTo = null,
                    viaPhone = false,
                    error = ended.problem,
                    hint = ended.hint
                )
            }
        }
    }

    fun open(target: CastTarget, title: String) {
        _state.value = CastUiState(
            target = target,
            title = title,
            devices = _state.value.devices,
            peers = _state.value.peers
        )
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
            // Kanal answers in under two seconds, UPnP takes three: the
            // devices with Kanal show up first rather than waiting for the rest.
            val peers = async {
                link.discover().also { _state.value = _state.value.copy(peers = it) }
            }
            val found = upnp.discover()
            peers.await()
            _state.value = _state.value.copy(devices = merge(found), searching = false)
        }
    }

    fun addByAddress(address: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(searching = true, error = null, hint = null)
            link.probe(address)?.let { peer ->
                prefs.rememberLinkAddress(address.trim())
                _state.value = _state.value.copy(
                    searching = false,
                    peers = (_state.value.peers + peer).distinctBy { it.id }
                )
                return@launch
            }
            val device = upnp.describeManual(address)
            if (device == null) {
                _state.value = _state.value.copy(
                    searching = false,
                    error = address.trim(),
                    hint = UiText(R.string.cast_no_answer_at_address)
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

    /** After Directo failed: the same thing, to the same television, through this phone. */
    fun sendViaPhone() {
        failedDevice?.let { sendTo(it, CastRouteMode.RELAY) }
    }

    fun sendTo(device: CastDevice, route: CastRouteMode? = null) {
        val target = _state.value.target ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(searching = true, error = null, hint = null, canTryRelay = false)
            val playable = resolve(target)
            if (playable == null) {
                _state.value = _state.value.copy(
                    searching = false,
                    error = "",
                    hint = UiText(R.string.cast_content_missing)
                )
                return@launch
            }
            cast.send(device, CastRequest.from(playable).copy(route = route))
                .onSuccess { outcome ->
                    sentDevice = device
                    lastDevice = device
                    _state.value = _state.value.copy(
                        searching = false,
                        sentTo = device.name,
                        viaPhone = outcome.viaPhone
                    )
                }
                .onFailure { failure ->
                    logger.w("Cast", "No se pudo enviar a ${device.name}", failure)
                    failedDevice = device
                    _state.value = _state.value.copy(
                        searching = false,
                        error = failure.message ?: failure.javaClass.simpleName,
                        hint = CastHints.of(failure),
                        canTryRelay = failure is DirectRefused
                    )
                }
        }
    }

    /** Another Kanal opens it in its own player, from its own catalogue if it can. */
    fun sendToPeer(peer: KanalPeer) {
        val target = _state.value.target ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(searching = true, error = null, hint = null, canTryRelay = false)
            val source = prefs.activeSource.first()
            val playable = resolve(target)
            if (playable == null) {
                _state.value = _state.value.copy(
                    searching = false,
                    error = "",
                    hint = UiText(R.string.cast_content_missing)
                )
                return@launch
            }
            val command = link.commandFor(playable, source)
            if (command.urls.isEmpty()) {
                _state.value = _state.value.copy(
                    searching = false,
                    error = "",
                    hint = UiText(R.string.link_send_local_only)
                )
                return@launch
            }
            link.play(peer, command)
                .onSuccess {
                    sentPeer = peer
                    _state.value = _state.value.copy(searching = false, sentTo = peer.name, viaPhone = false)
                }
                .onFailure { failure ->
                    _state.value = _state.value.copy(
                        searching = false,
                        error = failure.message ?: failure.javaClass.simpleName,
                        hint = UiText(R.string.link_send_failed, peer.name)
                    )
                }
        }
    }

    fun stopSending() {
        sentPeer?.let { peer ->
            sentPeer = null
            viewModelScope.launch {
                link.stop(peer)
                _state.value = _state.value.copy(sentTo = null, viaPhone = false)
            }
            return
        }
        val device = sentDevice ?: _state.value.devices.firstOrNull { it.name == _state.value.sentTo }
        sentDevice = null
        viewModelScope.launch {
            device?.let { cast.stop(it) }
            _state.value = _state.value.copy(sentTo = null, viaPhone = false)
        }
    }

    private suspend fun resolve(target: CastTarget): Playable? {
        val source = prefs.activeSource.first() ?: return null
        return when (target) {
            is CastTarget.Channel ->
                content.channel(source.id, target.streamId)?.let { playback.forChannel(source, it) }

            is CastTarget.Movie ->
                content.movie(source.id, target.movieId)?.let { playback.forMovie(source, it) }

            is CastTarget.Episode -> content.episode(source.id, target.episodeId)?.let { episode ->
                val series = content.seriesById(source.id, episode.seriesId)
                playback.forEpisode(source, episode, series?.name.orEmpty())
            }
        }
    }
}
