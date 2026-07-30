package com.mateof.kanal.ui.screens.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.res.stringResource
import com.mateof.kanal.R
import com.mateof.kanal.data.db.ChannelEntity
import com.mateof.kanal.data.db.MovieEntity
import com.mateof.kanal.data.db.SeriesEntity
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.repo.ContentRepository
import com.mateof.kanal.data.repo.EpgRepository
import com.mateof.kanal.ui.components.ChannelCard
import com.mateof.kanal.ui.components.MessageState
import com.mateof.kanal.ui.components.PosterCard
import com.mateof.kanal.ui.contentInset
import com.mateof.kanal.ui.screens.home.CardRow
import com.mateof.kanal.ui.theme.KanalColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    prefs: AppPreferences,
    content: ContentRepository,
    epg: EpgRepository
) : ViewModel() {

    private val activeSource = prefs.activeSource

    val channels: StateFlow<List<ChannelEntity>> = activeSource.flatMapLatest { source ->
        if (source == null) flowOf(emptyList()) else content.favoriteChannels(source.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val movies: StateFlow<List<MovieEntity>> = activeSource.flatMapLatest { source ->
        if (source == null) flowOf(emptyList()) else content.favoriteMovies(source.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val series: StateFlow<List<SeriesEntity>> = activeSource.flatMapLatest { source ->
        if (source == null) flowOf(emptyList()) else content.favoriteSeries(source.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val nowPlaying: StateFlow<Map<String, com.mateof.kanal.data.db.EpgEntity>> =
        activeSource.flatMapLatest { source ->
            if (source == null) flowOf(emptyMap()) else epg.nowPlaying(source.id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}

@Composable
fun FavoritesScreen(
    onOpenChannel: (String) -> Unit,
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit
) {
    val vm: FavoritesViewModel = hiltViewModel()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val movies by vm.movies.collectAsStateWithLifecycle()
    val series by vm.series.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()

    val empty = channels.isEmpty() && movies.isEmpty() && series.isEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 32.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Column(Modifier.padding(start = contentInset)) {
                Text(stringResource(R.string.nav_favorites), style = MaterialTheme.typography.headlineMedium, color = KanalColors.OnBackground)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${channels.size} canales · ${movies.size} películas · ${series.size} series",
                    style = MaterialTheme.typography.labelMedium,
                    color = KanalColors.OnSurfaceFaint
                )
            }
        }

        if (empty) {
            item {
                MessageState(
                    title = stringResource(R.string.favorites_empty_title),
                    description = stringResource(R.string.favorites_empty_body),
                    icon = Icons.Filled.Star,
                    modifier = Modifier.height(380.dp)
                )
            }
        }

        if (channels.isNotEmpty()) {
            item {
                CardRow(title = stringResource(R.string.common_channels)) {
                    items(channels, key = { it.streamId }) { channel ->
                        ChannelCard(
                            name = channel.name,
                            logoUrl = channel.logo,
                            number = channel.number,
                            nowTitle = nowPlaying[channel.epgChannelId]?.title.orEmpty(),
                            isFavorite = true,
                            onClick = { onOpenChannel(channel.streamId) }
                        )
                    }
                }
            }
        }
        if (movies.isNotEmpty()) {
            item {
                CardRow(title = stringResource(R.string.common_movies)) {
                    items(movies, key = { it.streamId }) { movie ->
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
        if (series.isNotEmpty()) {
            item {
                CardRow(title = stringResource(R.string.common_series)) {
                    items(series, key = { it.seriesId }) { serie ->
                        PosterCard(
                            title = serie.name,
                            imageUrl = serie.cover,
                            subtitle = serie.categoryName,
                            rating = serie.rating,
                            onClick = { onOpenSeries(serie.seriesId) }
                        )
                    }
                }
            }
        }
    }
}
