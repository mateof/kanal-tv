package com.mateof.kanal.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.kanal.core.asObject
import com.mateof.kanal.core.double
import com.mateof.kanal.core.firstStr
import com.mateof.kanal.core.int
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.db.EpisodeEntity
import com.mateof.kanal.data.db.MovieEntity
import com.mateof.kanal.data.db.SeriesEntity
import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.data.model.SourceType
import com.mateof.kanal.data.model.favoriteKey
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.repo.ContentRepository
import com.mateof.kanal.data.repo.SyncRepository
import com.mateof.kanal.data.xtream.XtreamClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MovieDetailState(
    val movie: MovieEntity? = null,
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val genre: String = "",
    val releaseDate: String = "",
    val durationSecs: Int = 0,
    val backdrop: String = "",
    val rating: Double = 0.0,
    val isFavorite: Boolean = false,
    val resumeMs: Long = 0L,
    val loading: Boolean = true,
    val error: String = ""
)

@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val content: ContentRepository,
    private val xtream: XtreamClient,
    private val logger: FileLogger
) : ViewModel() {

    private val _state = MutableStateFlow(MovieDetailState())
    val state: StateFlow<MovieDetailState> = _state.asStateFlow()

    private var loadedId = ""

    fun load(movieId: String) {
        if (loadedId == movieId) return
        loadedId = movieId
        viewModelScope.launch {
            val source = prefs.activeSource.first()
            if (source == null) {
                _state.value = MovieDetailState(loading = false, error = "No hay ninguna fuente activa.")
                return@launch
            }
            val movie = content.movie(source.id, movieId)
            if (movie == null) {
                _state.value = MovieDetailState(loading = false, error = "No se encontró la película.")
                return@launch
            }
            val favorites = prefs.favorites.first()
            _state.value = MovieDetailState(
                movie = movie,
                genre = movie.categoryName,
                rating = movie.rating,
                isFavorite = favorites.contains(favoriteKey(ContentKind.MOVIE, source.id, movieId)),
                resumeMs = prefs.resumePositionOf("${ContentKind.MOVIE.name}:${source.id}:$movieId"),
                loading = false
            )

            if (source.type != SourceType.XTREAM) return@launch
            // The catalogue listing has no plot or cast; get_vod_info does.
            val info = runCatching { xtream.vodInfo(source, movieId) }.getOrNull() ?: return@launch
            val details = info["info"].asObject()
            if (details != null) {
                _state.value = _state.value.copy(
                    plot = details.firstStr("plot", "description", "overview"),
                    cast = details.firstStr("cast", "actors"),
                    director = details.firstStr("director"),
                    genre = details.firstStr("genre").ifBlank { movie.categoryName },
                    releaseDate = details.firstStr("releasedate", "release_date"),
                    durationSecs = details.int("duration_secs"),
                    backdrop = details.firstStr("movie_image", "cover_big", "backdrop_path"),
                    rating = details.double("rating").takeIf { it > 0 } ?: movie.rating
                )
            }
        }
    }

    fun toggleFavorite() {
        val movie = _state.value.movie ?: return
        viewModelScope.launch {
            val source = prefs.activeSource.first() ?: return@launch
            val nowFavorite = prefs.toggleFavorite(ContentKind.MOVIE, source.id, movie.streamId)
            _state.value = _state.value.copy(isFavorite = nowFavorite)
            logger.d("Detail", "Favorito ${movie.name}: $nowFavorite")
        }
    }
}

data class SeriesDetailState(
    val series: SeriesEntity? = null,
    val seasons: List<Int> = emptyList(),
    val selectedSeason: Int = 1,
    val episodes: List<EpisodeEntity> = emptyList(),
    val isFavorite: Boolean = false,
    val loading: Boolean = true,
    val error: String = ""
)

@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val content: ContentRepository,
    private val sync: SyncRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SeriesDetailState())
    val state: StateFlow<SeriesDetailState> = _state.asStateFlow()

    private var loadedId = ""

    fun load(seriesId: String) {
        if (loadedId == seriesId) return
        loadedId = seriesId
        viewModelScope.launch {
            val source = prefs.activeSource.first()
            if (source == null) {
                _state.value = SeriesDetailState(loading = false, error = "No hay ninguna fuente activa.")
                return@launch
            }
            val series = content.seriesById(source.id, seriesId)
            if (series == null) {
                _state.value = SeriesDetailState(loading = false, error = "No se encontró la serie.")
                return@launch
            }
            val favorites = prefs.favorites.first()
            _state.value = SeriesDetailState(
                series = series,
                isFavorite = favorites.contains(favoriteKey(ContentKind.SERIES, source.id, seriesId)),
                loading = true
            )

            // Xtream only lists series in the catalogue; episodes need a second call.
            var episodes = content.episodes(source.id, seriesId).first()
            if (episodes.isEmpty() && source.type == SourceType.XTREAM) {
                sync.loadSeriesEpisodes(source, seriesId)
                episodes = content.episodes(source.id, seriesId).first()
            }
            val seasons = episodes.map { it.season }.distinct().sorted()
            _state.value = _state.value.copy(
                episodes = episodes,
                seasons = seasons,
                selectedSeason = seasons.firstOrNull() ?: 1,
                loading = false,
                error = if (episodes.isEmpty()) "Esta serie no tiene episodios disponibles." else ""
            )
        }
    }

    fun selectSeason(season: Int) {
        _state.value = _state.value.copy(selectedSeason = season)
    }

    fun toggleFavorite() {
        val series = _state.value.series ?: return
        viewModelScope.launch {
            val source = prefs.activeSource.first() ?: return@launch
            val nowFavorite = prefs.toggleFavorite(ContentKind.SERIES, source.id, series.seriesId)
            _state.value = _state.value.copy(isFavorite = nowFavorite)
        }
    }
}
