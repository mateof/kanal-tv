package com.mateof.kanal.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
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
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.repo.ContentRepository
import com.mateof.kanal.data.repo.SearchResults
import com.mateof.kanal.ui.components.ChannelCard
import com.mateof.kanal.ui.components.MessageState
import com.mateof.kanal.ui.components.PosterCard
import com.mateof.kanal.ui.components.SearchField
import com.mateof.kanal.ui.screens.home.CardRow
import com.mateof.kanal.ui.theme.KanalColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val content: ContentRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow(SearchResults())
    val results: StateFlow<SearchResults> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var job: Job? = null

    /** Debounced: on a remote every keystroke is a laborious D-pad journey. */
    fun onQueryChange(value: String) {
        _query.value = value
        job?.cancel()
        if (value.trim().length < 2) {
            _results.value = SearchResults()
            _searching.value = false
            return
        }
        job = viewModelScope.launch {
            delay(300)
            _searching.value = true
            val source = prefs.activeSource.first()
            _results.value = if (source == null) SearchResults() else content.search(source.id, value)
            _searching.value = false
        }
    }
}

@Composable
fun SearchScreen(
    onOpenChannel: (String) -> Unit,
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit
) {
    val vm: SearchViewModel = hiltViewModel()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 32.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Column(Modifier.padding(start = 40.dp, end = 60.dp)) {
                Text("Buscar", style = MaterialTheme.typography.headlineMedium, color = KanalColors.OnBackground)
                Spacer(Modifier.height(16.dp))
                SearchField(
                    value = query,
                    onValueChange = vm::onQueryChange,
                    placeholder = "Canal, película o serie…",
                    modifier = Modifier.fillMaxWidth(0.6f)
                )
                if (searching) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Buscando…",
                        style = MaterialTheme.typography.labelMedium,
                        color = KanalColors.OnSurfaceFaint
                    )
                } else if (query.trim().length >= 2) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${results.total} resultados",
                        style = MaterialTheme.typography.labelMedium,
                        color = KanalColors.OnSurfaceFaint
                    )
                }
            }
        }

        if (query.trim().length < 2) {
            item {
                MessageState(
                    title = "Escribe al menos dos letras",
                    description = "Kanal busca en los canales, las películas y las series de la fuente activa.",
                    icon = Icons.Outlined.Search,
                    modifier = Modifier.height(340.dp)
                )
            }
        } else if (results.isEmpty && !searching) {
            item {
                MessageState(
                    title = "Sin resultados",
                    description = "Prueba con otro término.",
                    icon = Icons.Outlined.Search,
                    modifier = Modifier.height(340.dp)
                )
            }
        }

        if (results.channels.isNotEmpty()) {
            item {
                CardRow(title = "Canales", trailing = "${results.channels.size}") {
                    items(results.channels, key = { it.streamId }) { channel ->
                        ChannelCard(
                            name = channel.name,
                            logoUrl = channel.logo,
                            number = channel.number,
                            onClick = { onOpenChannel(channel.streamId) }
                        )
                    }
                }
            }
        }
        if (results.movies.isNotEmpty()) {
            item {
                CardRow(title = "Películas", trailing = "${results.movies.size}") {
                    items(results.movies, key = { it.streamId }) { movie ->
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
        if (results.series.isNotEmpty()) {
            item {
                CardRow(title = "Series", trailing = "${results.series.size}") {
                    items(results.series, key = { it.seriesId }) { serie ->
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
