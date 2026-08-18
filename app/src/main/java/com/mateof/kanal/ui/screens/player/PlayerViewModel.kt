package com.mateof.kanal.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.mateof.kanal.R
import com.mateof.kanal.core.SleepTimer
import com.mateof.kanal.core.UiText
import com.mateof.kanal.cast.CastDevice
import com.mateof.kanal.cast.UpnpClient
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.db.ChannelEntity
import com.mateof.kanal.data.db.EpgEntity
import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.data.model.Source
import com.mateof.kanal.data.model.favoriteKey
import com.mateof.kanal.data.net.redactUrl
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.prefs.Settings
import com.mateof.kanal.data.repo.ContentRepository
import com.mateof.kanal.data.repo.EpgRepository
import com.mateof.kanal.data.repo.Playable
import com.mateof.kanal.data.repo.PlaybackRepository
import com.mateof.kanal.player.PlayerFactory
import com.mateof.kanal.player.StreamProbe
import com.mateof.kanal.player.PipController
import com.mateof.kanal.player.PlayerHandover
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

/** Ticks of half a second between progress writes. */
private const val SAVE_EVERY_TICKS = 30

/** Grace for the provider to notice the connection has gone. */
private const val SLOT_RELEASE_MS = 1_200L

/** Tiles loaded either side of the selection for the channel strip. */
private const val STRIP_WINDOW = 40

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

/**
 * A channel being looked at without being tuned to.
 *
 * Up and down walk the list while the current channel carries on playing, so
 * the guide can be checked before committing to a change. Holds its own copy of
 * what to show, because none of it belongs to what is on screen.
 */
data class BrowseInfo(
    val index: Int,
    val title: String,
    val subtitle: String,
    val logo: String,
    val now: EpgEntity? = null,
    val next: EpgEntity? = null
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
    /** Non-null while the arrows are walking the list instead of zapping. */
    val browse: BrowseInfo? = null,
    /** Every channel that can be zapped to, in order; the strip's tiles. */
    val channelIds: List<String> = emptyList(),
    /** Rows loaded so far for those ids, keyed by stream id. */
    val stripChannels: Map<String, ChannelEntity> = emptyMap(),
    /** True from queueing a new channel until its first frame is on screen. */
    val switching: Boolean = false,
    /** Non-zero while silently reconnecting after a dropout. */
    val reconnectAttempt: Int = 0,
    val castDevices: List<CastDevice> = emptyList(),
    val castSearching: Boolean = false,
    /** Name of the device the stream was handed to, if any. */
    val castingTo: String? = null,
    /** Why the last attempt failed, shown as-is: the UPnP code is the clue. */
    val castError: String? = null,
    /** Plain-language reading of the renderer's refusal, when it is known. */
    val castHint: UiText? = null,
    /** What the server actually answered, when a stream would not play. */
    val errorDetail: String? = null,
    /** True while what is playing can be made a favourite at all. */
    val canFavorite: Boolean = false,
    val isFavorite: Boolean = false,
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
    private val handover: PlayerHandover,
    private val pip: PipController,
    private val sleepTimer: SleepTimer,
    private val probe: StreamProbe,
    private val upnp: UpnpClient,
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
    private var favoritesJob: Job? = null
    private var browseJob: Job? = null
    private var reconnects = 0

    /** Where the arrows have walked to, kept out of the state so it can lead it. */
    private var browseIndex: Int? = null

    /** Channel rows fetched for the strip, kept across openings. */
    private val stripRows = mutableMapOf<String, ChannelEntity>()

    /** Stamped when a stream is queued, to time how long the picture takes. */
    private var startedAt = 0L

    /** Identifies what is playing, for the handover to match on. */
    private var streamKey = ""
    private var signature = ""

    /** Mirrors "recordar el último canal": without it there is nobody to hand to. */
    private var keepChannelOnExit = false
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
                _state.value = _state.value.copy(channelIds = channelIds)
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
        startedAt = System.currentTimeMillis()
        val settings = prefs.settings.first()
        resilient = settings.resilientPlayback
        keepChannelOnExit = settings.keepLastChannel
        reconnectJob?.cancel()
        reconnects = 0
        signature = buildSignature(settings, playable.userAgent)
        val previousKey = streamKey
        streamKey = playable.url

        // The preview may already be playing this very channel. Taking its
        // player over keeps the connection and the buffer, so opening a channel
        // from the list is instant instead of reconnecting from scratch.
        var adoptedFromPreview = false
        val adopted = player ?: handover.adopt(streamKey, signature)?.also {
            it.addListener(listener)
            player = it
            adoptedFromPreview = true
        }
        val fresh = adopted == null
        val exo = adopted ?: playerFactory.create(
            playable.userAgent,
            settings.bufferProfile,
            settings.resilientPlayback,
            settings.subtitlesEnabled
        ).also {
            it.addListener(listener)
            player = it
        }

        candidates = listOf(playable.url) + playable.fallbackUrls
        candidateIndex = 0

        // Only a player taken over from the preview is already showing this
        // stream. Anything else has to be pointed at it — including the player
        // we already had, which is the case when changing channel. Skipping this
        // left the previous channel running: it carried on until it happened to
        // fail, and only then did the retry pick up the new url, which is what
        // made a change of channel take seconds and sound like the old one.
        val alreadyPlayingThis = adoptedFromPreview || (!fresh && previousKey == playable.url)
        if (!alreadyPlayingThis) {
            // Stopped first and on purpose: a channel change should cut, not
            // fade. It also frees the provider's connection slot before the
            // next request asks for another one.
            exo.stop()
            exo.clearMediaItems()
            _state.value = _state.value.copy(switching = true)
            exo.setMediaItem(playerFactory.mediaItem(playable.url, playable.title))
            exo.prepare()
            if (playable.startPositionMs > 0) exo.seekTo(playable.startPositionMs)
        }
        exo.playWhenReady = true

        val index = channelIds.indexOf(playable.itemId)
        _state.value = _state.value.copy(
            playable = playable,
            loading = false,
            error = null,
            errorDetail = null,
            channelIndex = index,
            channelCount = channelIds.size,
            reconnectAttempt = 0
        )
        logger.i("Player", "Reproduciendo '${playable.title}'")

        loadGuide(playable)
        watchFavorites(playable)
        startTicker()
    }

    /**
     * Keeps the menu's favourite entry honest for whatever is playing now.
     *
     * Read from the stored set rather than kept as a local flag, so marking a
     * channel here and marking it in the list cannot disagree. Zapping restarts
     * the subscription, since the answer belongs to the channel, not the player.
     *
     * An episode is deliberately left out: favourites of that kind are keyed by
     * series, and an episode id in that slot would save something the series
     * page could never find again.
     */
    private fun watchFavorites(playable: Playable) {
        favoritesJob?.cancel()
        val kind = playable.kind
        if (kind != ContentKind.LIVE && kind != ContentKind.MOVIE) {
            _state.value = _state.value.copy(canFavorite = false, isFavorite = false)
            return
        }
        val key = favoriteKey(kind, playable.sourceId, playable.itemId)
        favoritesJob = viewModelScope.launch {
            prefs.favorites.collect { keys ->
                _state.value = _state.value.copy(
                    canFavorite = true,
                    isFavorite = keys.contains(key)
                )
            }
        }
    }

    fun toggleFavorite() {
        val playable = _state.value.playable ?: return
        if (!_state.value.canFavorite) return
        viewModelScope.launch {
            val nowFavorite = prefs.toggleFavorite(playable.kind, playable.sourceId, playable.itemId)
            logger.d("Player", "Favorito '${playable.title}': $nowFavorite")
        }
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
            // Progress is written every so often as well as on pause and on the
            // way out: a film left running when the app is killed from the
            // launcher would otherwise resume from where it last paused.
            var sinceSave = 0
            while (true) {
                val exo = player
                if (exo != null) {
                    if (++sinceSave >= SAVE_EVERY_TICKS && exo.isPlaying) {
                        sinceSave = 0
                        recordProgress()
                    }
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
            examineFailure(error)
        }

        // The new channel is on screen, so the picture is worth keeping again if
        // the connection stumbles later.
        override fun onRenderedFirstFrame() {
            if (_state.value.switching) _state.value = _state.value.copy(switching = false)
            // A picture is the only proof that this url was the right one, so
            // the note is taken here and not when playback merely started.
            val playable = _state.value.playable ?: return
            viewModelScope.launch { playback.rememberWorkingCandidate(playable, candidateIndex) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // The floating window is given the picture's own shape, so a 4:3
            // channel does not end up letterboxed inside a 16:9 box.
            pip.setVideoSize(videoSize.width, videoSize.height)
        }

        override fun onTracksChanged(tracks: Tracks) {
            _state.value = _state.value.copy(
                audioTracks = optionsFor(tracks, C.TRACK_TYPE_AUDIO),
                subtitleTracks = optionsFor(tracks, C.TRACK_TYPE_TEXT)
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && startedAt > 0) {
                logger.i("Player", "Primera imagen en ${System.currentTimeMillis() - startedAt} ms")
                startedAt = 0
            }
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
     * Asks the url that just failed what it was really serving.
     *
     * Only for the failures where the answer is in doubt — a container nothing
     * could read. A timeout or a refused connection already says what happened.
     */
    private fun examineFailure(error: PlaybackException) {
        if (!isContainerProblem(error)) return
        val url = candidates.getOrNull(candidateIndex) ?: return
        val userAgent = _state.value.playable?.userAgent ?: return
        viewModelScope.launch {
            val detail = probe.describe(url, userAgent)
            if (detail != null) _state.value = _state.value.copy(errorDetail = detail)
        }
    }

    private fun isContainerProblem(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> true

        else -> false
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
        val isSubtitle = group.type == C.TRACK_TYPE_TEXT
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            // Picking a subtitle has to lift the type-wide block as well, or the
            // override is set on a track type that stays switched off.
            .apply { if (isSubtitle) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false) }
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
        if (isSubtitle) rememberSubtitles(true)
    }

    fun disableSubtitles() {
        val exo = player ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        rememberSubtitles(false)
    }

    /** Subtitles are off until asked for, and the answer outlives the channel. */
    private fun rememberSubtitles(enabled: Boolean) = viewModelScope.launch {
        prefs.setSubtitlesEnabled(enabled)
    }

    fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    fun searchCastDevices() {
        if (_state.value.castSearching) return
        viewModelScope.launch {
            _state.value = _state.value.copy(castSearching = true)
            val found = upnp.discover()
            _state.value = _state.value.copy(castDevices = found, castSearching = false)
        }
    }

    /** Adds a device typed in by hand, for televisions discovery misses. */
    fun addCastDevice(address: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(castSearching = true, castError = null, castHint = null)
            val device = upnp.describeManual(address)
            _state.value = if (device == null) {
                _state.value.copy(
                    castSearching = false,
                    castError = "No responde en esa dirección"
                )
            } else {
                _state.value.copy(
                    castSearching = false,
                    castDevices = (_state.value.castDevices + device).distinctBy { it.id }
                )
            }
        }
    }

    /**
     * Hands the stream to [device].
     *
     * Local playback is stopped *first*, and that order matters. Most IPTV
     * accounts allow very few simultaneous connections — often exactly one — and
     * the television fetches the stream itself. Asking it to play something this
     * app is still holding gets the second client refused by the provider, which
     * the renderer reports back as "716 Resource not found": an error that points
     * at the URL and hides the real cause. Pausing is not enough either, since a
     * paused player keeps its connection open.
     */
    fun castTo(device: CastDevice) {
        val playable = _state.value.playable ?: return
        val url = candidates.getOrNull(candidateIndex) ?: playable.url
        viewModelScope.launch {
            val resumeFrom = player?.currentPosition ?: 0L
            player?.stop()
            // Providers do not always free the slot the instant the socket goes.
            delay(SLOT_RELEASE_MS)

            upnp.play(device, url, playable.title)
                .onSuccess {
                    _state.value = _state.value.copy(castingTo = device.name, castError = null, castHint = null)
                }
                .onFailure { failure ->
                    logger.w("Cast", "No se pudo enviar a ${device.name}", failure)
                    resumeLocally(resumeFrom)
                    // Shown verbatim on purpose: "no se pudo enviar" is useless
                    // when the renderer already said exactly what it objects to.
                    val detail = failure.message ?: "Error desconocido"
                    _state.value = _state.value.copy(
                        castError = detail,
                        castHint = hintFor(detail)
                    )
                }
        }
    }

    /** Stops the other device and takes the stream back here. */
    /** True while the app is the little floating window. */
    val inPip: StateFlow<Boolean> = pip.active

    /** Asks the activity to shrink into a floating window. */
    fun enterPip() = pip.request()

    // --- Sleep timer ---------------------------------------------------------
    //
    // The same singleton the settings screen arms, so the two cannot disagree
    // about whether a countdown is running. Offered here as well because the
    // moment somebody decides to fall asleep to something is the moment they
    // are already watching it, and leaving the film to find a setting is
    // precisely what they do not want to do.

    /** Milliseconds left on the countdown, or null when none is armed. */
    val sleepRemainingMs: StateFlow<Long?> = sleepTimer.remainingMs

    fun startSleep(minutes: Int) = sleepTimer.start(minutes)

    fun cancelSleep() = sleepTimer.cancel()

    fun stopCast() {
        val device = _state.value.castDevices.firstOrNull { it.name == _state.value.castingTo }
        val resumeFrom = _state.value.positionMs
        viewModelScope.launch {
            device?.let { upnp.stop(it) }
            _state.value = _state.value.copy(castingTo = null)
            delay(SLOT_RELEASE_MS)
            resumeLocally(resumeFrom)
        }
    }

    /**
     * A UPnP code is exact but says nothing about what to do. This turns the
     * handful that actually come up into something the user can act on.
     */
    private fun hintFor(detail: String): UiText? = when {
        detail.contains("UPnP 716") -> UiText(R.string.cast_hint_716)
        detail.contains("UPnP 714") -> UiText(R.string.cast_hint_714)
        detail.contains("UPnP 701") -> UiText(R.string.cast_hint_701)
        detail.contains("UPnP 402") -> UiText(R.string.cast_hint_402)
        detail.contains("UPnP 401") -> UiText(R.string.cast_hint_401)
        else -> null
    }

    /** Restarts playback here after the connection was handed over or given up. */
    private fun resumeLocally(positionMs: Long) {
        val exo = player ?: return
        exo.prepare()
        if (positionMs > 0 && _state.value.playable?.isLive == false) exo.seekTo(positionMs)
        exo.playWhenReady = true
    }

    /** Absolute seek, used by the progress bar. */
    fun seekTo(positionMs: Long) {
        val exo = player ?: return
        if (_state.value.playable?.isLive == true) return
        val duration = exo.duration.takeIf { it > 0 } ?: return
        exo.seekTo(positionMs.coerceIn(0L, duration))
        _state.value = _state.value.copy(positionMs = exo.currentPosition.coerceAtLeast(0))
        recordProgress()
    }

    fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        if (_state.value.playable?.isLive == true) return
        exo.seekTo((exo.currentPosition + deltaMs).coerceAtLeast(0))
    }

    /**
     * Moves the browsed position by [delta] without touching what is playing.
     *
     * The index advances at once and the details are filled in when the read
     * lands, so holding the arrow down runs along the list at the speed of the
     * remote rather than the speed of the database.
     *
     * @return false when there is nothing to walk through.
     */
    fun browse(delta: Int): Boolean {
        val from = browseIndex ?: _state.value.channelIndex
        if (from < 0) return false
        return browseTo((from + delta).mod(channelIds.size.coerceAtLeast(1)))
    }

    /** Walks straight to [target], for a tap on the strip or for opening it. */
    fun browseTo(target: Int): Boolean {
        val current = source ?: return false
        if (target !in channelIds.indices) return false
        browseIndex = target
        loadStripWindow(target)
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            val channel = content.channel(current.id, channelIds[target]) ?: return@launch
            val nowNext = channel.epgChannelId
                .takeIf { it.isNotBlank() }
                ?.let { epg.nowNext(current.id, it) }
            _state.value = _state.value.copy(
                browse = BrowseInfo(
                    index = target,
                    title = channel.name,
                    subtitle = channel.categoryName,
                    logo = channel.logo,
                    now = nowNext?.now,
                    next = nowNext?.next
                )
            )
        }
        return true
    }

    /**
     * Fills in the tiles around [center] that are not loaded yet.
     *
     * A window rather than the whole list: the strip only ever shows a handful,
     * and a large playlist would cost megabytes to hold in full for them.
     */
    private fun loadStripWindow(center: Int) {
        val current = source ?: return
        if (channelIds.isEmpty()) return
        val from = (center - STRIP_WINDOW).coerceAtLeast(0)
        val to = (center + STRIP_WINDOW).coerceAtMost(channelIds.lastIndex)
        val missing = (from..to).map { channelIds[it] }.filterNot { stripRows.containsKey(it) }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            content.channelsByIds(current.id, missing).forEach { stripRows[it.streamId] = it }
            _state.value = _state.value.copy(stripChannels = stripRows.toMap())
        }
    }

    /** Opens the strip on whatever is playing, so it starts where the eye is. */
    fun openStrip() {
        val index = _state.value.channelIndex
        if (index >= 0) browseTo(index) else loadStripWindow(0)
    }

    /** Gives up on the browsed channel and goes back to reporting the current one. */
    fun cancelBrowse() {
        if (browseIndex == null && _state.value.browse == null) return
        browseIndex = null
        browseJob?.cancel()
        _state.value = _state.value.copy(browse = null)
    }

    /**
     * Tunes to whatever was being browsed.
     *
     * @return false when nothing was being browsed, so the caller can treat the
     *   press as the plain "show me what is on" it then is.
     */
    fun playBrowsed(): Boolean {
        val target = browseIndex ?: return false
        val current = source ?: return false
        cancelBrowse()
        if (target == _state.value.channelIndex) return false

        viewModelScope.launch {
            val channel = content.channel(current.id, channelIds[target]) ?: return@launch
            val playable = playback.forChannel(current, channel)
            _state.value = _state.value.copy(channelIndex = target, error = null, buffering = true)
            start(playable)
        }
        return true
    }

    fun retry() {
        val exo = player ?: return
        reconnects = 0
        _state.value = _state.value.copy(
            error = null,
            errorDetail = null,
            buffering = true,
            reconnectAttempt = 0
        )
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
        pip.clearVideo()
        recordProgress()
        reconnectJob?.cancel()
        ticker?.cancel()
        val exo = player
        // A live channel is handed to the preview instead of being torn down;
        // anything else has nowhere to go and is released.
        if (exo != null && _state.value.playable?.isLive == true && keepChannelOnExit) {
            handover.park(exo, streamKey, signature, listener)
        } else {
            exo?.release()
        }
        player = null
        super.onCleared()
    }

    private fun buildSignature(settings: Settings, userAgent: String): String =
        "${settings.bufferProfile.name}|${settings.resilientPlayback}|" +
            "${settings.subtitlesEnabled}|$userAgent"

}
