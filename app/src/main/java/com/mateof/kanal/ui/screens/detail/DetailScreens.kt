package com.mateof.kanal.ui.screens.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.compose.ui.res.stringResource
import com.mateof.kanal.R
import com.mateof.kanal.core.resolve
import com.mateof.kanal.core.formatDuration
import com.mateof.kanal.data.db.EpisodeEntity
import com.mateof.kanal.ui.cast.CastSheet
import com.mateof.kanal.ui.cast.CastTarget
import com.mateof.kanal.ui.cast.CastViewModel
import com.mateof.kanal.ui.components.ArtworkImage
import com.mateof.kanal.ui.components.ButtonTone
import com.mateof.kanal.ui.components.ErrorState
import com.mateof.kanal.ui.components.FocusableSurface
import com.mateof.kanal.data.download.DownloadItem
import com.mateof.kanal.data.download.DownloadState
import com.mateof.kanal.ui.components.KanalButton
import com.mateof.kanal.ui.components.KanalChip
import com.mateof.kanal.ui.components.LoadingState
import com.mateof.kanal.ui.isCompact
import com.mateof.kanal.ui.theme.KanalColors

@Composable
fun MovieDetailScreen(
    movieId: String,
    onPlay: (String) -> Unit,
    onBack: () -> Unit
) {
    val vm: MovieDetailViewModel = hiltViewModel()
    val castVm: CastViewModel = hiltViewModel()
    val download by vm.download.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val castState by castVm.state.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val compact = isCompact

    LaunchedEffect(movieId) { vm.load(movieId) }
    BackHandler { onBack() }

    Box(Modifier.fillMaxSize()) {
    when {
        state.loading -> LoadingState(stringResource(R.string.detail_loading))
        state.movie == null -> ErrorState(state.error?.resolve().orEmpty()) { KanalButton(stringResource(R.string.common_back), onBack) }
        else -> {
            val movie = state.movie!!

            val poster: @Composable () -> Unit = {
                Box(
                    Modifier
                        .width(if (compact) 160.dp else 250.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(KanalColors.Surface)
                ) {
                    ArtworkImage(movie.cover, movie.name, Icons.Outlined.Movie)
                }
            }

            val body: @Composable (Modifier) -> Unit = { bodyModifier ->
                Column(bodyModifier) {
                    Text(
                        movie.name,
                        style = if (compact) {
                            MaterialTheme.typography.headlineSmall
                        } else {
                            MaterialTheme.typography.displaySmall
                        },
                        color = KanalColors.OnBackground
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        listOfNotNull(
                            state.releaseDate.takeIf { it.isNotBlank() },
                            state.genre.takeIf { it.isNotBlank() },
                            state.durationSecs.takeIf { it > 0 }?.let { formatDuration(it * 1000L) },
                            state.rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) }
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelLarge,
                        color = KanalColors.OnSurfaceMuted
                    )

                    if (state.plot.isNotBlank()) {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            state.plot,
                            style = if (compact) {
                                MaterialTheme.typography.bodyMedium
                            } else {
                                MaterialTheme.typography.bodyLarge
                            },
                            color = KanalColors.OnSurfaceMuted
                        )
                    }
                    if (state.cast.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.detail_cast, state.cast),
                            style = MaterialTheme.typography.bodySmall,
                            color = KanalColors.OnSurfaceFaint,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (state.director.isNotBlank()) {
                        Text(
                            stringResource(R.string.detail_director, state.director),
                            style = MaterialTheme.typography.bodySmall,
                            color = KanalColors.OnSurfaceFaint
                        )
                    }

                    Spacer(Modifier.height(28.dp))
                    DetailActions(
                        compact = compact,
                        playLabel = if (state.resumeMs > 0) {
                            stringResource(R.string.detail_resume, formatDuration(state.resumeMs))
                        } else {
                            stringResource(R.string.detail_play)
                        },
                        onPlay = { onPlay(movie.streamId) },
                        isFavorite = state.isFavorite,
                        onToggleFavorite = vm::toggleFavorite,
                        onBack = onBack,
                        onCast = { castVm.open(CastTarget.Movie(movie.streamId), movie.name) },
                        downloadLabel = downloadLabel(download),
                        onDownload = {
                            if (download?.state == DownloadState.FAILED) {
                                vm.retryDownload()
                            } else if (download?.isDone != true) {
                                vm.download()
                            }
                        }
                    )
                    message?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            it.resolve(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = KanalColors.Accent
                        )
                    }
                }
            }

            Box(Modifier.fillMaxSize()) {
                // The backdrop only earns its place on a wide screen; upright it
                // would just sit behind the text making it harder to read.
                if (state.backdrop.isNotBlank() && !compact) {
                    AsyncImage(
                        model = state.backdrop,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth(0.62f)
                            .fillMaxHeight()
                            .align(Alignment.CenterEnd)
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    0f to KanalColors.Background,
                                    0.55f to KanalColors.Background.copy(alpha = 0.92f),
                                    1f to Color.Transparent
                                )
                            )
                    )
                }

                if (compact) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        poster()
                        Spacer(Modifier.height(22.dp))
                        body(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(start = 56.dp, end = 56.dp, top = 48.dp, bottom = 40.dp)
                    ) {
                        poster()
                        Spacer(Modifier.width(40.dp))
                        body(
                            Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }
        CastSheet(
            state = castState,
            onSearch = castVm::search,
            onPick = castVm::sendTo,
            onAdd = castVm::addByAddress,
            onStop = castVm::stopSending,
            onClose = castVm::close
        )
    }
}

@Composable
fun SeriesDetailScreen(
    seriesId: String,
    onPlayEpisode: (String) -> Unit,
    onBack: () -> Unit
) {
    val vm: SeriesDetailViewModel = hiltViewModel()
    val castVm: CastViewModel = hiltViewModel()
    val episodeDownloads by vm.downloadsByEpisode.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val castState by castVm.state.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val compact = isCompact

    LaunchedEffect(seriesId) { vm.load(seriesId) }
    BackHandler { onBack() }

    Box(Modifier.fillMaxSize()) {
    when {
        state.loading -> LoadingState(stringResource(R.string.detail_loading_episodes))
        state.series == null -> ErrorState(state.error?.resolve().orEmpty()) { KanalButton(stringResource(R.string.common_back), onBack) }
        else -> {
            val series = state.series!!
            val episodes = state.episodes.filter { it.season == state.selectedSeason }

            if (compact) {
                // One list for everything: a scrolling column with a nested
                // episode list would be two vertical scrolls in a row.
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .width(160.dp)
                                    .aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(KanalColors.Surface)
                            ) {
                                ArtworkImage(series.cover, series.name, Icons.Outlined.Tv)
                            }
                            Spacer(Modifier.height(18.dp))
                        }
                    }
                    item {
                        SeriesHeading(
                            name = series.name,
                            meta = seriesMeta(
                                series.releaseDate,
                                series.genre,
                                series.rating,
                                state.episodes.size
                            ),
                            plot = series.plot,
                            compact = true
                        )
                    }
                    item {
                        DetailActions(
                            compact = true,
                            playLabel = null,
                            onPlay = {},
                            isFavorite = state.isFavorite,
                            onToggleFavorite = vm::toggleFavorite,
                            onBack = onBack,
                            downloadLabel = stringResource(R.string.download_action_season),
                            onDownload = vm::downloadSeason
                        )
                        message?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                it.resolve(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = KanalColors.Accent
                            )
                        }
                    }
                    if (state.seasons.size > 1) {
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(state.seasons) { season ->
                                    KanalChip(
                                        label = stringResource(R.string.detail_season, season),
                                        selected = season == state.selectedSeason,
                                        onClick = { vm.selectSeason(season) }
                                    )
                                }
                            }
                        }
                    }
                    state.error?.let { errorText ->
                        item {
                            Text(
                                errorText.resolve(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = KanalColors.Warning
                            )
                        }
                    }
                    items(episodes, key = { it.episodeId }) { episode ->
                        EpisodeRow(
                            episode,
                            compact = true,
                            download = episodeDownloads[episode.episodeId],
                            onDownload = { vm.downloadEpisode(episode.episodeId) },
                            onLongClick = {
                                castVm.open(CastTarget.Episode(episode.episodeId), episode.title)
                            }
                        ) { onPlayEpisode(episode.episodeId) }
                    }
                }
                return
            }

            Row(
                Modifier
                    .fillMaxSize()
                    .padding(start = 56.dp, end = 48.dp, top = 44.dp, bottom = 36.dp)
            ) {
                Column(Modifier.width(280.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(KanalColors.Surface)
                    ) {
                        ArtworkImage(series.cover, series.name, Icons.Outlined.Tv)
                    }
                    Spacer(Modifier.height(18.dp))
                    KanalButton(
                        text = stringResource(R.string.download_action_season),
                        onClick = vm::downloadSeason,
                        icon = Icons.Outlined.Download,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    KanalButton(
                        text = if (state.isFavorite) stringResource(R.string.detail_in_favorites) else stringResource(R.string.detail_add_favorite),
                        onClick = vm::toggleFavorite,
                        icon = Icons.Filled.Star,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    KanalButton(text = stringResource(R.string.common_back), onClick = onBack, modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.width(40.dp))

                Column(Modifier.weight(1f)) {
                    SeriesHeading(
                        name = series.name,
                        meta = seriesMeta(
                            series.releaseDate,
                            series.genre,
                            series.rating,
                            state.episodes.size
                        ),
                        plot = series.plot,
                        compact = false
                    )

                    if (state.seasons.size > 1) {
                        Spacer(Modifier.height(20.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(state.seasons) { season ->
                                KanalChip(
                                    label = stringResource(R.string.detail_season, season),
                                    selected = season == state.selectedSeason,
                                    onClick = { vm.selectSeason(season) }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    val errorText = state.error
                    if (errorText != null) {
                        Text(errorText.resolve(), style = MaterialTheme.typography.bodyMedium, color = KanalColors.Warning)
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 40.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(episodes, key = { it.episodeId }) { episode ->
                                EpisodeRow(
                                    episode,
                                    compact = false,
                                    download = episodeDownloads[episode.episodeId],
                                    onDownload = { vm.downloadEpisode(episode.episodeId) },
                                    onLongClick = {
                                        castVm.open(CastTarget.Episode(episode.episodeId), episode.title)
                                    }
                                ) { onPlayEpisode(episode.episodeId) }
                            }
                        }
                    }
                }
            }
        }
    }
        CastSheet(
            state = castState,
            onSearch = castVm::search,
            onPick = castVm::sendTo,
            onAdd = castVm::addByAddress,
            onStop = castVm::stopSending,
            onClose = castVm::close
        )
    }
}

private fun seriesMeta(releaseDate: String, genre: String, rating: Double, episodes: Int): String =
    listOfNotNull(
        releaseDate.takeIf { it.isNotBlank() },
        genre.takeIf { it.isNotBlank() },
        rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
        "$episodes episodios"
    ).joinToString("  ·  ")

@Composable
private fun SeriesHeading(name: String, meta: String, plot: String, compact: Boolean) {
    Column {
        Text(
            name,
            style = if (compact) {
                MaterialTheme.typography.headlineSmall
            } else {
                MaterialTheme.typography.displaySmall
            },
            color = KanalColors.OnBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(10.dp))
        Text(meta, style = MaterialTheme.typography.labelLarge, color = KanalColors.OnSurfaceMuted)
        if (plot.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(
                plot,
                style = MaterialTheme.typography.bodyMedium,
                color = KanalColors.OnSurfaceMuted,
                maxLines = if (compact) 6 else 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Upright the buttons stack full width; on a TV they sit in a row. */
/** What the download button says, given what the copy is doing. */
@Composable
private fun downloadLabel(item: DownloadItem?): String = when (item?.state) {
    null -> stringResource(R.string.download_action)
    DownloadState.QUEUED -> stringResource(R.string.download_state_queued)
    DownloadState.WAITING -> stringResource(R.string.downloads_waiting_wifi)
    DownloadState.RUNNING ->
        "${stringResource(R.string.download_state_running)} ${(item.progress * 100).toInt()}%"
    DownloadState.DONE -> stringResource(R.string.download_downloaded)
    DownloadState.FAILED -> stringResource(R.string.download_retry)
}

@Composable
private fun DetailActions(
    compact: Boolean,
    playLabel: String?,
    onPlay: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onBack: () -> Unit,
    /** Sending straight from here never opens a connection on this device. */
    onCast: (() -> Unit)? = null,
    /** Null hides the button: a live channel has nothing to keep. */
    downloadLabel: String? = null,
    onDownload: (() -> Unit)? = null
) {
    val favoriteLabel = if (isFavorite) stringResource(R.string.detail_in_favorites) else stringResource(R.string.detail_add_favorite)
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (playLabel != null) {
                KanalButton(
                    text = playLabel,
                    onClick = onPlay,
                    icon = Icons.Outlined.PlayArrow,
                    tone = ButtonTone.Primary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            onCast?.let {
                KanalButton(
                    text = stringResource(R.string.cast_send),
                    onClick = it,
                    icon = Icons.Outlined.Cast,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (downloadLabel != null && onDownload != null) {
                KanalButton(
                    text = downloadLabel,
                    onClick = onDownload,
                    icon = Icons.Outlined.Download,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            KanalButton(
                text = favoriteLabel,
                onClick = onToggleFavorite,
                icon = Icons.Filled.Star,
                modifier = Modifier.fillMaxWidth()
            )
            KanalButton(text = stringResource(R.string.common_back), onClick = onBack, modifier = Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (playLabel != null) {
                KanalButton(
                    text = playLabel,
                    onClick = onPlay,
                    icon = Icons.Outlined.PlayArrow,
                    tone = ButtonTone.Primary
                )
            }
            onCast?.let {
                KanalButton(text = stringResource(R.string.cast_send), onClick = it, icon = Icons.Outlined.Cast)
            }
            if (downloadLabel != null && onDownload != null) {
                KanalButton(text = downloadLabel, onClick = onDownload, icon = Icons.Outlined.Download)
            }
            KanalButton(text = favoriteLabel, onClick = onToggleFavorite, icon = Icons.Filled.Star)
            KanalButton(text = stringResource(R.string.common_back), onClick = onBack)
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: EpisodeEntity,
    compact: Boolean,
    download: DownloadItem? = null,
    onDownload: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    FocusableSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = KanalColors.Surface,
        focusedColor = KanalColors.SurfaceVariant,
        focusedScale = 1.01f
    ) { focused ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compact) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(if (compact) 92.dp else 120.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(KanalColors.BackgroundElevated)
            ) {
                ArtworkImage(episode.cover, episode.title, Icons.Outlined.Tv)
            }
            Spacer(Modifier.width(if (compact) 12.dp else 16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${episode.number}. ${episode.title}",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (focused) KanalColors.Accent else KanalColors.OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (episode.durationSecs > 0) {
                    Text(
                        formatDuration(episode.durationSecs * 1000L),
                        style = MaterialTheme.typography.labelSmall,
                        color = KanalColors.OnSurfaceFaint
                    )
                }
                if (episode.plot.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        episode.plot,
                        style = MaterialTheme.typography.bodySmall,
                        color = KanalColors.OnSurfaceMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (onDownload != null) {
                Spacer(Modifier.width(10.dp))
                // Its own target rather than a menu: one press per episode is
                // the whole point, and with the remote it is simply the next
                // thing to the right of the row.
                EpisodeDownloadMark(download, onDownload)
            }
        }
    }
}

@Composable
private fun EpisodeDownloadMark(item: DownloadItem?, onDownload: () -> Unit) {
    when (item?.state) {
        DownloadState.DONE -> Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = stringResource(R.string.download_downloaded),
            tint = KanalColors.Accent,
            modifier = Modifier.size(20.dp)
        )

        DownloadState.RUNNING, DownloadState.QUEUED, DownloadState.WAITING -> Text(
            text = if (item.state == DownloadState.RUNNING) {
                "${(item.progress * 100).toInt()}%"
            } else {
                stringResource(R.string.download_state_queued)
            },
            style = MaterialTheme.typography.labelSmall,
            color = KanalColors.OnSurfaceMuted
        )

        else -> FocusableSurface(
            onClick = onDownload,
            shape = RoundedCornerShape(10.dp),
            color = Color.Transparent,
            focusedColor = KanalColors.SurfaceVariant
        ) {
            Icon(
                Icons.Outlined.Download,
                contentDescription = stringResource(R.string.download_action),
                tint = if (item?.state == DownloadState.FAILED) KanalColors.Error else KanalColors.OnSurfaceMuted,
                modifier = Modifier.padding(8.dp).size(20.dp)
            )
        }
    }
}
