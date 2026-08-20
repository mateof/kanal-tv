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
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.mateof.kanal.R
import com.mateof.kanal.core.resolve
import com.mateof.kanal.core.formatClock
import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.data.repo.SyncState
import com.mateof.kanal.ui.Routes
import com.mateof.kanal.ui.contentInset
import com.mateof.kanal.ui.isCompact
import com.mateof.kanal.ui.components.ButtonTone
import com.mateof.kanal.ui.components.ChannelCard
import com.mateof.kanal.ui.components.ContinueCard
import com.mateof.kanal.ui.components.ActionMenu
import com.mateof.kanal.ui.components.MenuAction
import com.mateof.kanal.data.model.Source
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
    val sources by vm.sources.collectAsStateWithLifecycle()
    var pickingSource by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.refreshIfStale()
        updateVm.checkAutomatically()
    }

    if (state.loading) {
        LoadingState(stringResource(R.string.home_preparing))
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
                sources = sources,
                onPickSource = { pickingSource = true },
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
                val label = (syncState as? SyncState.Running)?.step?.resolve() ?: stringResource(R.string.home_syncing)
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
                    title = stringResource(R.string.home_empty_title),
                    description = stringResource(R.string.home_empty_body),
                    icon = Icons.Outlined.LiveTv,
                    modifier = Modifier.height(360.dp)
                ) {
                    KanalButton(stringResource(R.string.home_sync_now), vm::refresh, tone = ButtonTone.Primary)
                }
            }
        }

        if (state.continueWatching.isNotEmpty()) {
            item {
                CardRow(title = stringResource(R.string.home_continue)) {
                    items(state.continueWatching, key = { it.key }) { entry ->
                        ContinueCard(
                            title = entry.name,
                            subtitle = when (entry.kind) {
                                ContentKind.SERIES -> stringResource(R.string.home_kind_series)
                                ContentKind.MOVIE -> stringResource(R.string.home_kind_movie)
                                ContentKind.LIVE -> stringResource(R.string.home_kind_live)
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
                CardRow(title = stringResource(R.string.home_favorite_channels), trailing = "${state.favoriteChannels.size}") {
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
                CardRow(title = stringResource(R.string.home_recent)) {
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
                CardRow(title = stringResource(R.string.home_new_movies), trailing = stringResource(R.string.home_see_all)) {
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
                CardRow(title = stringResource(R.string.home_new_series), trailing = stringResource(R.string.home_see_all)) {
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
                    KanalButton(stringResource(R.string.home_go_live), { onNavigate(Routes.LIVE) }, tone = ButtonTone.Primary)
                    KanalButton(stringResource(R.string.nav_search), { onNavigate(Routes.SEARCH) })
                    KanalButton(stringResource(R.string.nav_settings), { onNavigate(Routes.SETTINGS) })
                }
            }
        }
    }
    // After the list, never before: composed first it ends up underneath,
    // with the buttons of the screen behind showing through the card.
    if (pickingSource) {
        ActionMenu(
            title = stringResource(R.string.home_change_source),
            onDismiss = { pickingSource = false },
            actions = sources.map { source ->
                MenuAction(
                    label = source.name,
                    icon = Icons.Outlined.SwapHoriz,
                    active = source.id == state.source?.id
                ) {
                    vm.useSource(source.id)
                    pickingSource = false
                }
            }
        )
    }
}

@Composable
private fun HomeHeader(
    sourceName: String,
    channels: Int,
    movies: Int,
    series: Int,
    refreshing: Boolean,
    sources: List<Source>,
    onPickSource: () -> Unit,
    onRefresh: () -> Unit
) {
    val tally = listOf(
        pluralStringResource(R.plurals.count_channels, channels, channels),
        pluralStringResource(R.plurals.count_movies, movies, movies),
        pluralStringResource(R.plurals.count_series, series, series)
    ).joinToString(" · ")
    val counts = if (sourceName.isNotBlank()) "$sourceName · $tally" else tally
    val refreshButton: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KanalButton(
                text = if (refreshing) stringResource(R.string.home_syncing) else stringResource(R.string.home_refresh),
                onClick = onRefresh,
                icon = Icons.Outlined.Refresh,
                enabled = !refreshing
            )
            // Only worth a button when there is somewhere to switch to.
            if (sources.size > 1) {
                KanalButton(
                    text = stringResource(R.string.home_change_source),
                    onClick = onPickSource,
                    icon = Icons.Outlined.SwapHoriz
                )
            }
        }
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

@Composable
private fun greeting(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        hour < 6 -> stringResource(R.string.home_evening)
        hour < 13 -> stringResource(R.string.home_morning)
        hour < 21 -> stringResource(R.string.home_afternoon)
        else -> stringResource(R.string.home_evening)
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
