package com.mateof.kanal.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.mateof.kanal.R
import com.mateof.kanal.core.UiText
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.db.EpgEntity
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.net.redactUrl
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

/** How many silent reconnections before the user is told something is wrong. */
private const val MAX_RECONNECTS = 6

data class TrackOption(
    val id: String,
    /** What the provider called the track, or null when it did not name it. */
    val name: String?,
    /** Codec and channel count, already formatted; may be empty. */
    val details: String,
    /** 1-based position, used to name a track the provider left unnamed. */
    val number: Int,
    val selected: Boolean,
    val groupIndex: Int,
    val trackIndex: Int
)

data class PlayerUiState(
    val playable: Playable? = null,
    val loading: Boolean = true,
    val error: UiText? = null,
    val buffering: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val now: EpgEntity? = null,
    val next: EpgEntity? = null,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val channelIndex: Int = -1,
    val channelCount: Int = 0,
    /** Non-zero while silently reconnecting after a dropout. */
    val reconnectAttempt: Int = 0,
    /** Days the provider sent guide for, for the in-player guide panel. */
    val guideDays: List<Long> = emptyList(),
    val selectedDay: Long = 0L,
    val dayProgrammes: List<EpgEntity> = emptyList()
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

    /** Urls still to try for the current item, in order. */
    private var candidates: List<String> = emptyList()
    private var candidateIndex = 0
    private var reconnectJob: Job? = null
    private var reconnects = 0
    private var resilient = false

    fun load(kind: String, itemId: String, startMillis: Long) {
        val key = "$kind/$itemId/$startMillis"
        if (loadedKey == key) return
        loadedKey = key

        viewModelScope.launch {
            val current = prefs.activeSource.first()
            if (current == null) {
                _state.value = _state.value.copy(loading = false, error = UiText(R.string.error_no_source))
                return@launch
            }
            source = current

            val playable = resolve(current, kind, itemId, startMillis)
            if (playable == null) {
                _state.value = _state.value.copy(loading = false, error = UiText(R.string.error_not_found))
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
        resilient = settings.resilientPlayback
        reconnectJob?.cancel()
        reconnects = 0
        val exo = player ?: playerFactory.create(
            playable.userAgent,
            settings.bufferProfile,
            settings.resilientPlayback
        ).also {
            it.addListener(listener)
            player = it
        }

        candidates = listOf(playable.url) + playable.fallbackUrls
        candidateIndex = 0

        exo.setMediaItem(playerFactory.mediaItem(playable.url, playable.title))
        exo.prepare()
        if (playable.startPositionMs > 0) exo.seekTo(playable.startPositionMs)
        exo.playWhenReady = true

        val index = channelIds.indexOf(playable.itemId)
        _state.value = _state.value.copy(
            playable = playable,
            loading = false,
            error = null,
            channelIndex = index,
            channelCount = channelIds.size,
            reconnectAttempt = 0
        )
        logger.i("Player", "Reproduciendo '${playable.title}'")

        loadGuide(playable)
        startTicker()
    }

    private fun loadGuide(playable: Playable) {
        if (!playable.isLive || playable.epgChannelId.isBlank()) {
            _state.value = _state.value.copy(
                now = null,
                next = null,
                guideDays = emptyList(),
                dayProgrammes = emptyList()
            )
            return
        }
        viewModelScope.launch {
            val current = source ?: return@launch
            val channelId = playable.epgChannelId
            val nowNext = epg.nowNext(current.id, channelId)
            val days = epg.availableDays(current.id, channelId)
            val today = days.firstOrNull() ?: 0L
            _state.value = _state.value.copy(
                now = nowNext.now,
                next = nowNext.next,
                guideDays = days,
                selectedDay = today,
                dayProgrammes = if (today > 0) {
                    epg.programmesOfDay(current.id, channelId, today)
                } else {
                    emptyList()
                }
            )
        }
    }

    fun selectGuideDay(day: Long) {
        val channelId = _state.value.playable?.epgChannelId.orEmpty()
        val current = source ?: return
        if (channelId.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                selectedDay = day,
                dayProgrammes = epg.programmesOfDay(current.id, channelId, day)
            )
        }
    }

    /** Refreshes now/next so the OSD is right after a programme changes over. */
    fun refreshNowNext() {
        val playable = _state.value.playable ?: return
        if (!playable.isLive || playable.epgChannelId.isBlank()) return
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
            logger.w("Player", "Error de reproducción: ${error.errorCodeName}")
            if (tryNextCandidate(error)) return
            if (scheduleReconnect(error)) return
            logger.e("Player", "Sin más formatos que probar", error)
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
            // A clean run means the dropout is over; let it earn its retries back.
            if (isPlaying && _state.value.reconnectAttempt > 0) {
                reconnects = 0
                _state.value = _state.value.copy(reconnectAttempt = 0)
            }
            _state.value = _state.value.copy(playing = isPlaying)
            if (!isPlaying) recordProgress()
        }
    }

    /**
     * Panels are inconsistent channel by channel: some only answer in MPEG-TS,
     * some only in HLS, and a few still use the prefix-less path. When the
     * failure looks like "this is not what I asked for", walk the alternatives
     * instead of showing the user an error they cannot act on.
     *
     * @return true when another url was queued.
     */
    private fun tryNextCandidate(error: PlaybackException): Boolean {
        if (!isWorthRetrying(error)) return false
        if (candidateIndex + 1 >= candidates.size) return false
        val exo = player ?: return false

        candidateIndex++
        val url = candidates[candidateIndex]
        val title = _state.value.playable?.title.orEmpty()
        logger.i("Player", "Reintentando '$title' con ${redactUrl(url)}")

        _state.value = _state.value.copy(error = null, buffering = true)
        exo.setMediaItem(playerFactory.mediaItem(url, title))
        exo.prepare()
        exo.playWhenReady = true
        return true
    }

    private fun isWorthRetrying(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true

        else -> false
    }

    /**
     * A stream that dies mid-programme is almost always the provider hiccuping,
     * not something the user can act on. Rather than dropping an error on the
     * screen, reconnect quietly a few times and only give up if it keeps failing.
     *
     * @return true when a reconnection was queued.
     */
    private fun scheduleReconnect(error: PlaybackException): Boolean {
        if (!resilient) return false
        if (!isRecoverable(error)) return false
        if (reconnects >= MAX_RECONNECTS) return false
        val exo = player ?: return false

        reconnects++
        val delayMs = (1_000L * reconnects).coerceAtMost(6_000L)
        logger.i("Player", "Reconectando en ${delayMs}ms (intento $reconnects/$MAX_RECONNECTS)")
        _state.value = _state.value.copy(
            reconnectAttempt = reconnects,
            error = null,
            buffering = true
        )

        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(delayMs)
            // Always come back on the candidate that was working.
            candidates.getOrNull(candidateIndex)?.let { url ->
                exo.setMediaItem(playerFactory.mediaItem(url, _state.value.playable?.title.orEmpty()))
            }
            exo.prepare()
            exo.playWhenReady = true
        }
        return true
    }

    /**
     * Only reconnect when the *connection* broke. Decoding and container errors
     * usually mean the source itself is damaged — a weak aerial feeding a DVB
     * tuner, for instance — and restarting the stream on every corrupt frame
     * replaces a momentary artefact with a full rebuffer. Riding it out is
     * better, and ExoPlayer already skips what it cannot decode.
     */
    private fun isRecoverable(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_TIMEOUT -> true

        else -> false
    }

    private fun optionsFor(tracks: Tracks, type: Int): List<TrackOption> {
        val options = mutableListOf<TrackOption>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != type) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
                val name = (format.label ?: language)?.let { base ->
                    if (language != null && format.label != null) "$base ($language)" else base
                }
                val details = buildString {
                    format.codecs?.let { append(" · $it") }
                    if (type == C.TRACK_TYPE_AUDIO && format.channelCount > 0) {
                        append(" · ${format.channelCount}ch")
                    }
                }
                options += TrackOption(
                    id = "$groupIndex:$trackIndex",
                    name = name,
                    details = details,
                    number = options.size + 1,
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
            _state.value = _state.value.copy(channelIndex = target, error = null)
            start(playable)
        }
        return true
    }

    fun retry() {
        val exo = player ?: return
        reconnects = 0
        _state.value = _state.value.copy(error = null, buffering = true, reconnectAttempt = 0)
        // Start the candidate walk over: the channel may simply have been down.
        candidateIndex = 0
        candidates.firstOrNull()?.let { url ->
            exo.setMediaItem(playerFactory.mediaItem(url, _state.value.playable?.title.orEmpty()))
        }
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

    private fun friendlyError(error: PlaybackException): UiText = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            UiText(R.string.error_no_connection)

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            UiText(R.string.error_rejected)

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            UiText(R.string.error_gone)

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
            UiText(R.string.error_container)

        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
            UiText(R.string.error_decode)

        else -> UiText(R.string.error_generic, error.errorCodeName)
    }

    override fun onCleared() {
        recordProgress()
        reconnectJob?.cancel()
        ticker?.cancel()
        player?.release()
        player = null
        super.onCleared()
    }
}
