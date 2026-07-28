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
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tv
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
import com.mateof.kanal.core.formatDuration
import com.mateof.kanal.ui.components.ArtworkImage
import com.mateof.kanal.ui.components.ButtonTone
import com.mateof.kanal.ui.components.ErrorState
import com.mateof.kanal.ui.components.FocusableSurface
import com.mateof.kanal.ui.components.KanalButton
import com.mateof.kanal.ui.components.KanalChip
import com.mateof.kanal.ui.components.LoadingState
import com.mateof.kanal.ui.theme.KanalColors

@Composable
fun MovieDetailScreen(
    movieId: String,
    onPlay: (String) -> Unit,
    onBack: () -> Unit
) {
    val vm: MovieDetailViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(movieId) { vm.load(movieId) }
    BackHandler { onBack() }

    when {
        state.loading -> LoadingState("Cargando ficha…")
        state.movie == null -> ErrorState(state.error) { KanalButton("Volver", onBack) }
        else -> {
            val movie = state.movie!!
            Box(Modifier.fillMaxSize()) {
                if (state.backdrop.isNotBlank()) {
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

                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 56.dp, end = 56.dp, top = 48.dp, bottom = 40.dp)
                ) {
                    Box(
                        Modifier
                            .width(250.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(KanalColors.Surface)
                    ) {
                        ArtworkImage(movie.cover, movie.name, Icons.Outlined.Movie)
                    }
                    Spacer(Modifier.width(40.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            movie.name,
                            style = MaterialTheme.typography.displaySmall,
                            color = KanalColors.OnBackground
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            listOfNotNull(
                                state.releaseDate.takeIf { it.isNotBlank() },
                                state.genre.takeIf { it.isNotBlank() },
                                state.durationSecs.takeIf { it > 0 }
                                    ?.let { formatDuration(it * 1000L) },
                                state.rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) }
                            ).joinToString("  ·  "),
                            style = MaterialTheme.typography.labelLarge,
                            color = KanalColors.OnSurfaceMuted
                        )

                        if (state.plot.isNotBlank()) {
                            Spacer(Modifier.height(20.dp))
                            Text(
                                state.plot,
                                style = MaterialTheme.typography.bodyLarge,
                                color = KanalColors.OnSurfaceMuted
                            )
                        }
                        if (state.cast.isNotBlank()) {
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Reparto: ${state.cast}",
                                style = MaterialTheme.typography.bodySmall,
                                color = KanalColors.OnSurfaceFaint,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (state.director.isNotBlank()) {
                            Text(
                                "Dirección: ${state.director}",
                                style = MaterialTheme.typography.bodySmall,
                                color = KanalColors.OnSurfaceFaint
                            )
                        }

                        Spacer(Modifier.height(28.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            KanalButton(
                                text = if (state.resumeMs > 0) {
                                    "Continuar desde ${formatDuration(state.resumeMs)}"
                                } else {
                                    "Reproducir"
                                },
                                onClick = { onPlay(movie.streamId) },
                                icon = Icons.Outlined.PlayArrow,
                                tone = ButtonTone.Primary
                            )
                            KanalButton(
                                text = if (state.isFavorite) "En favoritos" else "Añadir a favoritos",
                                onClick = vm::toggleFavorite,
                                icon = Icons.Filled.Star
                            )
                            KanalButton(text = "Volver", onClick = onBack)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesDetailScreen(
    seriesId: String,
    onPlayEpisode: (String) -> Unit,
    onBack: () -> Unit
) {
    val vm: SeriesDetailViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(seriesId) { vm.load(seriesId) }
    BackHandler { onBack() }

    when {
        state.loading -> LoadingState("Cargando episodios…")
        state.series == null -> ErrorState(state.error) { KanalButton("Volver", onBack) }
        else -> {
            val series = state.series!!
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
                        text = if (state.isFavorite) "En favoritos" else "Añadir a favoritos",
                        onClick = vm::toggleFavorite,
                        icon = Icons.Filled.Star,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    KanalButton(text = "Volver", onClick = onBack, modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.width(40.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        series.name,
                        style = MaterialTheme.typography.displaySmall,
                        color = KanalColors.OnBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        listOfNotNull(
                            series.releaseDate.takeIf { it.isNotBlank() },
                            series.genre.takeIf { it.isNotBlank() },
                            series.rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                            "${state.episodes.size} episodios"
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelLarge,
                        color = KanalColors.OnSurfaceMuted
                    )
                    if (series.plot.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            series.plot,
                            style = MaterialTheme.typography.bodyMedium,
                            color = KanalColors.OnSurfaceMuted,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (state.seasons.size > 1) {
                        Spacer(Modifier.height(20.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(state.seasons) { season ->
                                KanalChip(
                                    label = "Temporada $season",
                                    selected = season == state.selectedSeason,
                                    onClick = { vm.selectSeason(season) }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    if (state.error.isNotBlank()) {
                        Text(state.error, style = MaterialTheme.typography.bodyMedium, color = KanalColors.Warning)
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 40.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                state.episodes.filter { it.season == state.selectedSeason },
                                key = { it.episodeId }
                            ) { episode ->
                                EpisodeRow(
                                    number = episode.number,
                                    title = episode.title,
                                    plot = episode.plot,
                                    durationSecs = episode.durationSecs,
                                    cover = episode.cover,
                                    onClick = { onPlayEpisode(episode.episodeId) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    number: Int,
    title: String,
    plot: String,
    durationSecs: Int,
    cover: String,
    onClick: () -> Unit
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = KanalColors.Surface,
        focusedColor = KanalColors.SurfaceVariant,
        focusedScale = 1.01f
    ) { focused ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(120.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(KanalColors.BackgroundElevated)
            ) {
                ArtworkImage(cover, title, Icons.Outlined.Tv)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "$number. $title",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (focused) KanalColors.Accent else KanalColors.OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (durationSecs > 0) {
                    Text(
                        formatDuration(durationSecs * 1000L),
                        style = MaterialTheme.typography.labelSmall,
                        color = KanalColors.OnSurfaceFaint
                    )
                }
                if (plot.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        plot,
                        style = MaterialTheme.typography.bodySmall,
                        color = KanalColors.OnSurfaceMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
