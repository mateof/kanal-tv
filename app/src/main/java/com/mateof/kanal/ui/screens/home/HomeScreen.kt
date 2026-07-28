package com.mateof.kanal.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mateof.kanal.core.formatClock
import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.data.repo.SyncState
import com.mateof.kanal.ui.Routes
import com.mateof.kanal.ui.contentInset
import com.mateof.kanal.ui.isCompact
import com.mateof.kanal.ui.components.ButtonTone
import com.mateof.kanal.ui.components.ChannelCard
import com.mateof.kanal.ui.components.ContinueCard
import com.mateof.kanal.ui.components.KanalButton
import com.mateof.kanal.ui.components.LoadingState
import com.mateof.kanal.ui.components.MessageState
import com.mateof.kanal.ui.components.PosterCard
import com.mateof.kanal.ui.components.SectionHeader
import com.mateof.kanal.ui.components.StepProgress
import com.mateof.kanal.ui.components.UpdateBanner
import com.mateof.kanal.ui.components.UpdateViewModel
import com.mateof.kanal.ui.theme.KanalColors
import com.mateof.kanal.ui.theme.Spacing

@Composable
fun HomeScreen(
    onOpenChannel: (String) -> Unit,
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onResume: (String, String) -> Unit,
    onNavigate: (String) -> Unit
) {
    val vm: HomeViewModel = hiltViewModel()
    val updateVm: UpdateViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val recentChannels by vm.recentChannels.collectAsStateWithLifecycle()
    val syncState by vm.syncState.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val updateState by updateVm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.refreshIfStale()
        updateVm.checkAutomatically()
    }

    if (state.loading) {
        LoadingState("Preparando tu tele…")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = if (isCompact) 0.dp else 8.dp,
            end = if (isCompact) 0.dp else Spacing.screenHorizontal,
            top = Spacing.screenVertical,
            bottom = 60.dp
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.section)
    ) {
        item {
            HomeHeader(
                sourceName = state.source?.name.orEmpty(),
                channels = state.channels,
                movies = state.movies,
                series = state.series,
                refreshing = refreshing,
                onRefresh = vm::refresh
            )
        }

        if (updateState.available != null) {
            item {
                UpdateBanner(
                    state = updateState,
                    onUpdate = updateVm::downloadAndInstall,
                    onDismiss = updateVm::dismiss
                )
            }
        }

        if (refreshing) {
            item {
                val label = (syncState as? SyncState.Running)?.step ?: "Sincronizando…"
                val progress = (syncState as? SyncState.Running)?.progress ?: -1f
                StepProgress(
                    label,
                    progress,
                    Modifier.padding(start = contentInset, end = if (isCompact) contentInset else 200.dp)
                )
            }
        }

        if (state.isEmpty && !refreshing) {
            item {
                MessageState(
                    title = "Todavía no hay contenido",
                    description = "Sincroniza la fuente para descargar canales, películas y series.",
                    icon = Icons.Outlined.LiveTv,
                    modifier = Modifier.height(360.dp)
                ) {
                    KanalButton("Sincronizar ahora", vm::refresh, tone = ButtonTone.Primary)
                }
            }
        }

        if (state.continueWatching.isNotEmpty()) {
            item {
                CardRow(title = "Continuar viendo") {
                    items(state.continueWatching, key = { it.key }) { entry ->
                        ContinueCard(
                            title = entry.name,
                            subtitle = when (entry.kind) {
                                ContentKind.SERIES -> "Serie"
                                ContentKind.MOVIE -> "Película"
                                ContentKind.LIVE -> "Directo"
                            },
                            imageUrl = entry.logo,
                            progress = entry.progress,
                            onClick = { onResume(entry.kind.name, entry.itemId) }
                        )
                    }
                }
            }
        }

        if (state.favoriteChannels.isNotEmpty()) {
            item {
                CardRow(title = "Canales favoritos", trailing = "${state.favoriteChannels.size}") {
                    items(state.favoriteChannels, key = { it.streamId }) { channel ->
                        ChannelCard(
                            name = channel.name,
                            logoUrl = channel.logo,
                            number = channel.number,
                            nowTitle = state.nowPlaying[channel.epgChannelId]?.title.orEmpty(),
                            isFavorite = true,
                            onClick = { onOpenChannel(channel.streamId) }
                        )
                    }
                }
            }
        }

        if (recentChannels.isNotEmpty()) {
            item {
                CardRow(title = "Vistos hace poco") {
                    items(recentChannels, key = { it.streamId }) { channel ->
                        ChannelCard(
                            name = channel.name,
                            logoUrl = channel.logo,
                            number = channel.number,
                            nowTitle = state.nowPlaying[channel.epgChannelId]?.title.orEmpty(),
                            onClick = { onOpenChannel(channel.streamId) }
                        )
                    }
                }
            }
        }

        if (state.newMovies.isNotEmpty()) {
            item {
                CardRow(title = "Películas añadidas", trailing = "Ver todas") {
                    items(state.newMovies, key = { it.streamId }) { movie ->
                        PosterCard(
                            title = movie.name,
                            imageUrl = movie.cover,
                            subtitle = movie.categoryName,
                            rating = movie.rating,
                            onClick = { onOpenMovie(movie.streamId) }
                        )
                    }
                }
            }
        }

        if (state.newSeries.isNotEmpty()) {
            item {
                CardRow(title = "Series añadidas", trailing = "Ver todas") {
                    items(state.newSeries, key = { it.seriesId }) { series ->
                        PosterCard(
                            title = series.name,
                            imageUrl = series.cover,
                            subtitle = series.categoryName,
                            rating = series.rating,
                            onClick = { onOpenSeries(series.seriesId) }
                        )
                    }
                }
            }
        }

        if (!state.isEmpty) {
            item {
                Row(
                    modifier = Modifier.padding(start = contentInset, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    KanalButton("Ir a TV en directo", { onNavigate(Routes.LIVE) }, tone = ButtonTone.Primary)
                    KanalButton("Buscar", { onNavigate(Routes.SEARCH) })
                    KanalButton("Ajustes", { onNavigate(Routes.SETTINGS) })
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    sourceName: String,
    channels: Int,
    movies: Int,
    series: Int,
    refreshing: Boolean,
    onRefresh: () -> Unit
) {
    val counts = buildString {
        if (sourceName.isNotBlank()) append(sourceName).append(" · ")
        append("$channels canales · $movies películas · $series series")
    }
    val refreshButton: @Composable () -> Unit = {
        KanalButton(
            text = if (refreshing) "Sincronizando…" else "Actualizar",
            onClick = onRefresh,
            icon = Icons.Outlined.Refresh,
            enabled = !refreshing
        )
    }

    if (isCompact) {
        // Upright the greeting, the counts and the button do not fit on one
        // line; the clock goes away too, the status bar already has it.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = contentInset, end = contentInset, bottom = 4.dp)
        ) {
            Text(
                greeting(),
                style = MaterialTheme.typography.headlineSmall,
                color = KanalColors.OnBackground,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                counts,
                style = MaterialTheme.typography.bodySmall,
                color = KanalColors.OnSurfaceMuted
            )
            Spacer(Modifier.height(12.dp))
            refreshButton()
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = contentInset, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                greeting(),
                style = MaterialTheme.typography.headlineLarge,
                color = KanalColors.OnBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(counts, style = MaterialTheme.typography.bodyMedium, color = KanalColors.OnSurfaceMuted)
        }
        Text(
            formatClock(System.currentTimeMillis()),
            style = MaterialTheme.typography.headlineSmall,
            color = KanalColors.OnSurfaceMuted
        )
        Spacer(Modifier.width(24.dp))
        refreshButton()
    }
}

private fun greeting(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        hour < 6 -> "Buenas noches"
        hour < 13 -> "Buenos días"
        hour < 21 -> "Buenas tardes"
        else -> "Buenas noches"
    }
}

/** Title + horizontal strip of cards, the row pattern used all over the app. */
@Composable
fun CardRow(
    title: String,
    trailing: String = "",
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(title, Modifier.padding(start = contentInset, end = 8.dp), trailing)
        Spacer(Modifier.height(14.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = contentInset, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            content = content
        )
    }
}
