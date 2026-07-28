package com.mateof.kanal.ui.screens.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.mateof.kanal.data.db.CategoryEntity
import com.mateof.kanal.data.repo.CatalogSort
import com.mateof.kanal.ui.components.FocusableSurface
import com.mateof.kanal.ui.components.KanalButton
import com.mateof.kanal.ui.components.KanalChip
import com.mateof.kanal.ui.components.MessageState
import com.mateof.kanal.ui.components.PosterCard
import com.mateof.kanal.ui.isCompact
import com.mateof.kanal.ui.theme.KanalColors

@Composable
fun MoviesScreen(onOpen: (String) -> Unit) {
    val vm: MoviesViewModel = hiltViewModel()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.category.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val items = vm.movies.collectAsLazyPagingItems()

    CatalogLayout(
        title = "Películas",
        icon = Icons.Outlined.Movie,
        categories = categories,
        selectedCategory = selected,
        sort = sort,
        onSelectCategory = vm::selectCategory,
        onCycleSort = vm::cycleSort,
        itemCount = items.itemCount
    ) {
        items(count = items.itemCount) { index ->
            val movie = items[index] ?: return@items
            PosterCard(
                title = movie.name,
                imageUrl = movie.cover,
                subtitle = movie.categoryName,
                rating = movie.rating,
                width = if (isCompact) null else 176.dp,
                onClick = { onOpen(movie.streamId) }
            )
        }
    }
}

@Composable
fun SeriesScreen(onOpen: (String) -> Unit) {
    val vm: SeriesViewModel = hiltViewModel()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selected by vm.category.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val items = vm.series.collectAsLazyPagingItems()

    CatalogLayout(
        title = "Series",
        icon = Icons.Outlined.Tv,
        categories = categories,
        selectedCategory = selected,
        sort = sort,
        onSelectCategory = vm::selectCategory,
        onCycleSort = vm::cycleSort,
        itemCount = items.itemCount
    ) {
        items(count = items.itemCount) { index ->
            val serie = items[index] ?: return@items
            PosterCard(
                title = serie.name,
                imageUrl = serie.cover,
                subtitle = serie.categoryName,
                rating = serie.rating,
                width = if (isCompact) null else 176.dp,
                onClick = { onOpen(serie.seriesId) }
            )
        }
    }
}

@Composable
private fun CatalogLayout(
    title: String,
    icon: ImageVector,
    categories: List<CategoryEntity>,
    selectedCategory: String,
    sort: CatalogSort,
    onSelectCategory: (String) -> Unit,
    onCycleSort: () -> Unit,
    itemCount: Int,
    grid: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit
) {
    if (isCompact) {
        CompactCatalog(
            title = title,
            icon = icon,
            categories = categories,
            selectedCategory = selectedCategory,
            sort = sort,
            onSelectCategory = onSelectCategory,
            onCycleSort = onCycleSort,
            itemCount = itemCount,
            grid = grid
        )
        return
    }

    Row(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .width(250.dp)
                .fillMaxHeight(),
            contentPadding = PaddingValues(start = 8.dp, end = 12.dp, top = 28.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                Text(
                    "Categorías",
                    style = MaterialTheme.typography.labelMedium,
                    color = KanalColors.OnSurfaceFaint,
                    modifier = Modifier.padding(start = 12.dp, bottom = 10.dp)
                )
            }
            item {
                CategoryRow("Todas", selectedCategory.isEmpty()) { onSelectCategory("") }
            }
            items(categories, key = { it.categoryId }) { category ->
                CategoryRow(category.name, category.categoryId == selectedCategory) {
                    onSelectCategory(category.categoryId)
                }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 48.dp, top = 28.dp, bottom = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, color = KanalColors.OnBackground)
                    Text(
                        "$itemCount elementos",
                        style = MaterialTheme.typography.labelMedium,
                        color = KanalColors.OnSurfaceFaint
                    )
                }
                KanalButton(text = sort.label, onClick = onCycleSort, icon = Icons.Outlined.Sort)
            }

            if (itemCount == 0) {
                MessageState(
                    title = "Nada por aquí",
                    description = "Sincroniza la fuente o prueba con otra categoría.",
                    icon = icon
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(190.dp),
                    contentPadding = PaddingValues(start = 20.dp, end = 48.dp, top = 8.dp, bottom = 60.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                    content = grid
                )
            }
        }
    }
}

@Composable
private fun CompactCatalog(
    title: String,
    icon: ImageVector,
    categories: List<CategoryEntity>,
    selectedCategory: String,
    sort: CatalogSort,
    onSelectCategory: (String) -> Unit,
    onCycleSort: () -> Unit,
    itemCount: Int,
    grid: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = KanalColors.OnBackground)
                Text(
                    "$itemCount elementos",
                    style = MaterialTheme.typography.labelSmall,
                    color = KanalColors.OnSurfaceFaint
                )
            }
            KanalButton(text = sort.label, onClick = onCycleSort, icon = Icons.Outlined.Sort)
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                KanalChip(
                    label = "Todas",
                    selected = selectedCategory.isEmpty(),
                    onClick = { onSelectCategory("") }
                )
            }
            items(categories, key = { it.categoryId }) { category ->
                KanalChip(
                    label = category.name,
                    selected = category.categoryId == selectedCategory,
                    onClick = { onSelectCategory(category.categoryId) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        if (itemCount == 0) {
            MessageState(
                title = "Nada por aquí",
                description = "Sincroniza la fuente o prueba con otra categoría.",
                icon = icon
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
                content = grid
            )
        }
    }
}

@Composable
private fun CategoryRow(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) KanalColors.SurfaceVariant else Color.Transparent,
        focusedColor = KanalColors.Accent,
        focusedScale = 1.0f
    ) { isFocused ->
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = when {
                isFocused -> Color(0xFF06231F)
                selected -> KanalColors.Accent
                else -> KanalColors.OnSurfaceMuted
            },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}
