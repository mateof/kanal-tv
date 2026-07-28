package com.mateof.kanal.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.db.EpgEntity
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.repo.ContentRepository
import com.mateof.kanal.data.repo.EpgRepository
import com.mateof.kanal.data.repo.Playable
import com.mateof.kanal.data.repo.PlaybackRepository
import com.mateof.kanal.player.PlayerFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrackOption(
    val id: String,
    val label: String,
    val selected: Boolean,
    val groupIndex: Int,
    val trackIndex: Int
)

data class PlayerUiState(
    val playable: Playable? = null,
    val loading: Boolean = true,
    val error: String = "",
    val buffering: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val now: EpgEntity? = null,
    val next: EpgEntity? = null,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val channelIndex: Int = -1,
    val channelCount: Int = 0
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val content: ContentRepository,
    private val playback: PlaybackRepository,
    private val epg: EpgRepository,
    private val playerFactory: PlayerFactory,
    private val logger: FileLogger
) : ViewModel() {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    var player: ExoPlayer? = null
        private set

    private var source: Source? = null
    private var channelIds: List<String> = emptyList()
    private var ticker: Job? = null
    private var loadedKey = ""

    fun load(kind: String, itemId: String, startMillis: Long) {
        val key = "$kind/$itemId/$startMillis"
        if (loadedKey == key) return
        loadedKey = key

        viewModelScope.launch {
            val current = prefs.activeSource.first()
            if (current == null) {
                _state.value = _state.value.copy(loading = false, error = "No hay ninguna fuente activa.")
                return@launch
            }
            source = current

            val playable = resolve(current, kind, itemId, startMillis)
            if (playable == null) {
                _state.value = _state.value.copy(loading = false, error = "No se encontró el contenido.")
                return@launch
            }

            if (playable.isLive || kind == "LIVE") {
                channelIds = content.channelIds(current.id, "", "")
            }

            start(playable)
        }
    }

    private suspend fun resolve(
        source: Source,
        kind: String,
        itemId: String,
        startMillis: Long
    ): Playable? = when (kind) {
        "MOVIE" -> content.movie(source.id, itemId)?.let { playback.forMovie(source, it) }
        "SERIES" -> content.episode(source.id, itemId)?.let { episode ->
            val series = content.seriesById(source.id, episode.seriesId)
            playback.forEpisode(source, episode, series?.name.orEmpty())
        }

        else -> content.channel(source.id, itemId)?.let { channel ->
            if (startMillis > 0) {
                val programme = epg.window(
                    source.id,
                    channel.epgChannelId,
                    startMillis - 1,
                    startMillis + 1
                ).firstOrNull { it.start == startMillis }
                if (programme != null) {
                    playback.forCatchup(source, channel, programme)
                        ?: playback.forChannel(source, channel)
                } else {
                    playback.forChannel(source, channel)
                }
            } else {
                playback.forChannel(source, channel)
            }
        }
    }

    private suspend fun start(playable: Playable) {
        val settings = prefs.settings.first()
        val exo = player ?: playerFactory.create(playable.userAgent, settings.bufferProfile).also {
            it.addListener(listener)
            player = it
        }

        exo.setMediaItem(playerFactory.mediaItem(playable.url, playable.title))
        exo.prepare()
        if (playable.startPositionMs > 0) exo.seekTo(playable.startPositionMs)
        exo.playWhenReady = true

        val index = channelIds.indexOf(playable.itemId)
        _state.value = _state.value.copy(
            playable = playable,
            loading = false,
            error = "",
            channelIndex = index,
            channelCount = channelIds.size
        )
        logger.i("Player", "Reproduciendo '${playable.title}'")

        loadGuide(playable)
        startTicker()
    }

    private fun loadGuide(playable: Playable) {
        if (!playable.isLive || playable.epgChannelId.isBlank()) {
            _state.value = _state.value.copy(now = null, next = null)
            return
        }
        viewModelScope.launch {
            val current = source ?: return@launch
            val nowNext = epg.nowNext(current.id, playable.epgChannelId)
            _state.value = _state.value.copy(now = nowNext.now, next = nowNext.next)
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                val exo = player
                if (exo != null) {
                    _state.value = _state.value.copy(
                        positionMs = exo.currentPosition.coerceAtLeast(0),
                        durationMs = exo.duration.takeIf { it > 0 } ?: 0L,
                        playing = exo.isPlaying,
                        buffering = exo.playbackState == Player.STATE_BUFFERING
                    )
                }
                delay(500)
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            logger.e("Player", "Error de reproducción: ${error.errorCodeName}", error)
            _state.value = _state.value.copy(
                error = friendlyError(error),
                buffering = false,
                playing = false
            )
        }

        override fun onTracksChanged(tracks: Tracks) {
            _state.value = _state.value.copy(
                audioTracks = optionsFor(tracks, C.TRACK_TYPE_AUDIO),
                subtitleTracks = optionsFor(tracks, C.TRACK_TYPE_TEXT)
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(playing = isPlaying)
            if (!isPlaying) recordProgress()
        }
    }

    private fun optionsFor(tracks: Tracks, type: Int): List<TrackOption> {
        val options = mutableListOf<TrackOption>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != type) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
                val label = buildString {
                    append(format.label ?: language ?: "Pista ${options.size + 1}")
                    if (language != null && format.label != null) append(" ($language)")
                    format.codecs?.let { append(" · $it") }
                    if (type == C.TRACK_TYPE_AUDIO && format.channelCount > 0) {
                        append(" · ${format.channelCount}ch")
                    }
                }
                options += TrackOption(
                    id = "$groupIndex:$trackIndex",
                    label = label,
                    selected = group.isTrackSelected(trackIndex),
                    groupIndex = groupIndex,
                    trackIndex = trackIndex
                )
            }
        }
        return options
    }

    fun selectTrack(option: TrackOption) {
        val exo = player ?: return
        val group = exo.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
    }

    fun disableSubtitles() {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        if (_state.value.playable?.isLive == true) return
        exo.seekTo((exo.currentPosition + deltaMs).coerceAtLeast(0))
    }

    /** @return false when there is nothing to zap to. */
    fun zap(delta: Int): Boolean {
        val current = source ?: return false
        if (channelIds.isEmpty()) return false
        val index = _state.value.channelIndex
        if (index < 0) return false
        val target = (index + delta).mod(channelIds.size)
        viewModelScope.launch {
            val channel = content.channel(current.id, channelIds[target]) ?: return@launch
            val playable = playback.forChannel(current, channel)
            _state.value = _state.value.copy(channelIndex = target, error = "")
            start(playable)
        }
        return true
    }

    fun retry() {
        val exo = player ?: return
        _state.value = _state.value.copy(error = "")
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun recordProgress() {
        val playable = _state.value.playable ?: return
        val exo = player ?: return
        val position = exo.currentPosition
        val duration = exo.duration.takeIf { it > 0 } ?: 0L
        viewModelScope.launch { playback.record(playable, position, duration) }
    }

    private fun friendlyError(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "No se pudo conectar con el servidor."

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "El servidor rechazó la emisión. ¿Demasiadas conexiones abiertas?"

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "La emisión ya no existe en el servidor."

        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
            "El dispositivo no puede decodificar esta emisión."

        else -> "No se pudo reproducir (${error.errorCodeName})."
    }

    override fun onCleared() {
        recordProgress()
        ticker?.cancel()
        player?.release()
        player = null
        super.onCleared()
    }
}
