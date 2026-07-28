package com.mateof.kanal.ui.screens.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.mateof.kanal.data.db.CategoryEntity
import com.mateof.kanal.data.db.MovieEntity
import com.mateof.kanal.data.db.SeriesEntity
import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.repo.CatalogSort
import com.mateof.kanal.data.repo.ContentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Shared state for the two catalogue grids (films and series). */
abstract class CatalogViewModel(
    protected val prefs: AppPreferences,
    protected val content: ContentRepository,
    private val kind: ContentKind
) : ViewModel() {

    protected val activeSource = prefs.activeSource

    private val _category = MutableStateFlow("")
    val category: StateFlow<String> = _category.asStateFlow()

    private val _sort = MutableStateFlow(CatalogSort.DEFAULT)
    val sort: StateFlow<CatalogSort> = _sort.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val categories: StateFlow<List<CategoryEntity>> = activeSource.flatMapLatest { source ->
        if (source == null) flowOf(emptyList()) else content.categories(source.id, kind)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectCategory(id: String) {
        _category.value = id
    }

    fun cycleSort() {
        val values = CatalogSort.entries
        _sort.value = values[(values.indexOf(_sort.value) + 1) % values.size]
    }

    protected fun filters(): Flow<Pair<String, CatalogSort>> =
        combine(_category, _sort) { category, sort -> category to sort }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MoviesViewModel @Inject constructor(
    prefs: AppPreferences,
    content: ContentRepository
) : CatalogViewModel(prefs, content, ContentKind.MOVIE) {

    val movies: Flow<PagingData<MovieEntity>> =
        combine(activeSource, filters()) { source, filters -> source to filters }
            .flatMapLatest { (source, filters) ->
                if (source == null) flowOf(PagingData.empty())
                else content.movies(source.id, filters.first, "", filters.second)
            }
            .cachedIn(viewModelScope)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SeriesViewModel @Inject constructor(
    prefs: AppPreferences,
    content: ContentRepository
) : CatalogViewModel(prefs, content, ContentKind.SERIES) {

    val series: Flow<PagingData<SeriesEntity>> =
        combine(activeSource, filters()) { source, filters -> source to filters }
            .flatMapLatest { (source, filters) ->
                if (source == null) flowOf(PagingData.empty())
                else content.series(source.id, filters.first, "", filters.second)
            }
            .cachedIn(viewModelScope)
}
