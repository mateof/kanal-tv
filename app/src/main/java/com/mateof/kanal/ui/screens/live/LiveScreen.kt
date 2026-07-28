package com.mateof.kanal.ui.screens.live

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.paging.compose.collectAsLazyPagingItems
import com.mateof.kanal.core.formatClock
import com.mateof.kanal.data.db.ChannelEntity
import com.mateof.kanal.data.db.EpgEntity
import com.mateof.kanal.ui.components.ArtworkImage
import com.mateof.kanal.ui.components.FocusableSurface
import com.mateof.kanal.ui.components.KanalButton
import com.mateof.kanal.ui.components.KanalChip
import com.mateof.kanal.ui.components.MessageState
import com.mateof.kanal.ui.components.ThinProgress
import com.mateof.kanal.ui.isCompact
import com.mateof.kanal.ui.theme.KanalColors

@Composable
fun LiveScreen(onPlay: (String, Long) -> Unit) {
    val vm: LiveViewModel = hiltViewModel()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selectedCategory by vm.selectedCategory.collectAsStateWithLifecycle()
    val focused by vm.focused.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val next by vm.next.collectAsStateWithLifecycle()
    val schedule by vm.schedule.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val previewActive by vm.previewActive.collectAsStateWithLifecycle()
    val previewError by vm.previewError.collectAsStateWithLifecycle()
    val favoriteChannels by vm.favoriteChannels.collectAsStateWithLifecycle()

    val paged = vm.channels.collectAsLazyPagingItems()
    val showingFavorites = selectedCategory == CATEGORY_FAVORITES

    DisposableEffect(Unit) { onDispose { vm.stopPreview() } }

    if (isCompact) {
        // Upright there is room for one column only: categories become a strip
        // of chips and the preview pane is dropped — on a phone a tap goes
        // straight to full screen anyway.
        CompactLive(
            categories = categories,
            selectedCategory = selectedCategory,
            channels = if (showingFavorites) favoriteChannels else null,
            paged = paged,
            nowPlaying = nowPlaying,
            favorites = favorites,
            sourceId = source?.id.orEmpty(),
            onSelectCategory = vm::selectCategory,
            onPlay = { id -> onPlay(id, 0L) }
        )
        return
    }

    Row(Modifier.fillMaxSize()) {
        // --- Categories -----------------------------------------------------
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
                CategoryRow("Todos los canales", selectedCategory == CATEGORY_ALL) {
                    vm.selectCategory(CATEGORY_ALL)
                }
            }
            item {
                CategoryRow("Favoritos", showingFavorites) { vm.selectCategory(CATEGORY_FAVORITES) }
            }
            items(categories, key = { it.categoryId }) { category ->
                CategoryRow(category.name, category.categoryId == selectedCategory) {
                    vm.selectCategory(category.categoryId)
                }
            }
        }

        // --- Channels -------------------------------------------------------
        Box(
            Modifier
                .width(420.dp)
                .fillMaxHeight()
        ) {
            if (showingFavorites) {
                if (favoriteChannels.isEmpty()) {
                    MessageState(
                        title = "Sin favoritos",
                        description = "Marca un canal con el botón de estrella para tenerlo aquí.",
                        icon = Icons.Filled.Star
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 28.dp, horizontal = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(favoriteChannels, key = { it.streamId }) { channel ->
                            ChannelListRow(
                                channel = channel,
                                now = nowPlaying[channel.epgChannelId],
                                isFavorite = true,
                                onFocused = { vm.onChannelFocused(channel) },
                                onClick = { onPlay(channel.streamId, 0L) }
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 28.dp, horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(count = paged.itemCount) { index ->
                        val channel = paged[index] ?: return@items
                        ChannelListRow(
                            channel = channel,
                            now = nowPlaying[channel.epgChannelId],
                            isFavorite = source?.let { favorites.contains("LIVE:${it.id}:${channel.streamId}") } == true,
                            onFocused = { vm.onChannelFocused(channel) },
                            onClick = { onPlay(channel.streamId, 0L) }
                        )
                    }
                    if (paged.itemCount == 0) {
                        item {
                            MessageState(
                                title = "No hay canales",
                                description = "Sincroniza la fuente desde Ajustes.",
                                icon = Icons.Outlined.LiveTv,
                                modifier = Modifier.height(320.dp)
                            )
                        }
                    }
                }
            }
        }

        // --- Preview + guide ------------------------------------------------
        DetailPane(
            channel = focused,
            now = now,
            next = next,
            schedule = schedule,
            previewActive = previewActive,
            previewError = previewError,
            player = vm.previewPlayer(),
            isFavorite = focused?.let { channel ->
                source?.let { favorites.contains("LIVE:${it.id}:${channel.streamId}") } == true
            } == true,
            onToggleFavorite = { focused?.let(vm::toggleFavorite) },
            onPlay = { focused?.let { onPlay(it.streamId, 0L) } },
            onPlayCatchup = { programme ->
                focused?.let { onPlay(it.streamId, programme.start) }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
    }
}

@Composable
private fun CompactLive(
    categories: List<com.mateof.kanal.data.db.CategoryEntity>,
    selectedCategory: String,
    channels: List<ChannelEntity>?,
    paged: androidx.paging.compose.LazyPagingItems<ChannelEntity>,
    nowPlaying: Map<String, EpgEntity>,
    favorites: Set<String>,
    sourceId: String,
    onSelectCategory: (String) -> Unit,
    onPlay: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "TV en directo",
            style = MaterialTheme.typography.headlineSmall,
            color = KanalColors.OnBackground,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                KanalChip(
                    label = "Todos",
                    selected = selectedCategory == CATEGORY_ALL,
                    onClick = { onSelectCategory(CATEGORY_ALL) }
                )
            }
            item {
                KanalChip(
                    label = "Favoritos",
                    selected = selectedCategory == CATEGORY_FAVORITES,
                    onClick = { onSelectCategory(CATEGORY_FAVORITES) }
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

        if (channels != null) {
            if (channels.isEmpty()) {
                MessageState(
                    title = "Sin favoritos",
                    description = "Marca un canal con la estrella para tenerlo aquí.",
                    icon = Icons.Filled.Star
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(channels, key = { it.streamId }) { channel ->
                        ChannelListRow(
                            channel = channel,
                            now = nowPlaying[channel.epgChannelId],
                            isFavorite = true,
                            onFocused = {},
                            onClick = { onPlay(channel.streamId) }
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(count = paged.itemCount) { index ->
                    val channel = paged[index] ?: return@items
                    ChannelListRow(
                        channel = channel,
                        now = nowPlaying[channel.epgChannelId],
                        isFavorite = favorites.contains("LIVE:$sourceId:${channel.streamId}"),
                        onFocused = {},
                        onClick = { onPlay(channel.streamId) }
                    )
                }
                if (paged.itemCount == 0) {
                    item {
                        MessageState(
                            title = "No hay canales",
                            description = "Sincroniza la fuente desde Ajustes.",
                            icon = Icons.Outlined.LiveTv,
                            modifier = Modifier.height(320.dp)
                        )
                    }
                }
            }
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

@Composable
private fun ChannelListRow(
    channel: ChannelEntity,
    now: EpgEntity?,
    isFavorite: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = KanalColors.Surface,
        focusedColor = KanalColors.SurfaceVariant,
        focusedScale = 1.02f,
        onFocusState = { if (it) onFocused() }
    ) { isFocused ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(62.dp, 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(KanalColors.BackgroundElevated)
            ) {
                ArtworkImage(
                    url = channel.logo,
                    label = channel.name,
                    fallbackIcon = Icons.Outlined.LiveTv,
                    contentScale = ContentScale.Fit,
                    padding = 6.dp
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isFocused) KanalColors.Accent else KanalColors.OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = now?.title ?: "Sin guía",
                    style = MaterialTheme.typography.labelSmall,
                    color = KanalColors.OnSurfaceFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (now != null) {
                    Spacer(Modifier.height(6.dp))
                    ThinProgress(progressOf(now), Modifier.fillMaxWidth())
                }
            }
            if (isFavorite) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Favorito",
                    tint = KanalColors.Warning,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun DetailPane(
    channel: ChannelEntity?,
    now: EpgEntity?,
    next: EpgEntity?,
    schedule: List<EpgEntity>,
    previewActive: Boolean,
    previewError: String,
    player: androidx.media3.exoplayer.ExoPlayer?,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit,
    onPlayCatchup: (EpgEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    if (channel == null) {
        MessageState(
            title = "Elige un canal",
            description = "Muévete por la lista para ver la vista previa y la guía.",
            icon = Icons.Outlined.LiveTv,
            modifier = modifier
        )
        return
    }

    Column(
        modifier = modifier.padding(start = 24.dp, end = 44.dp, top = 28.dp, bottom = 28.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black)
        ) {
            if (previewActive && player != null) {
                AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setPlayer(player)
                        }
                    },
                    update = { view -> view.player = player },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.size(120.dp, 70.dp)) {
                        ArtworkImage(
                            url = channel.logo,
                            label = channel.name,
                            fallbackIcon = Icons.Outlined.LiveTv,
                            contentScale = ContentScale.Fit
                        )
                    }
                    if (previewError.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            previewError,
                            style = MaterialTheme.typography.labelMedium,
                            color = KanalColors.Error
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            channel.name,
            style = MaterialTheme.typography.headlineSmall,
            color = KanalColors.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            buildString {
                if (channel.number > 0) append("Canal ${channel.number}")
                if (channel.categoryName.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(channel.categoryName)
                }
                if (channel.archiveDays > 0) append(" · Repetición ${channel.archiveDays} días")
            },
            style = MaterialTheme.typography.labelMedium,
            color = KanalColors.OnSurfaceFaint
        )

        if (now != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                "${formatClock(now.start)} – ${formatClock(now.stop)}  ·  ${now.title}",
                style = MaterialTheme.typography.titleMedium,
                color = KanalColors.Accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            ThinProgress(progressOf(now), Modifier.fillMaxWidth())
            if (now.description.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    now.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = KanalColors.OnSurfaceMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (next != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Después · ${formatClock(next.start)} ${next.title}",
                style = MaterialTheme.typography.bodySmall,
                color = KanalColors.OnSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KanalButton(
                text = "Ver ahora",
                onClick = onPlay,
                tone = com.mateof.kanal.ui.components.ButtonTone.Primary
            )
            KanalButton(
                text = if (isFavorite) "Quitar de favoritos" else "Añadir a favoritos",
                onClick = onToggleFavorite,
                icon = Icons.Filled.Star
            )
        }

        if (schedule.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Text(
                "Programación",
                style = MaterialTheme.typography.titleSmall,
                color = KanalColors.OnSurfaceMuted
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(schedule, key = { it.start }) { programme ->
                    ScheduleRow(
                        programme = programme,
                        canReplay = channel.archiveDays > 0 && programme.stop < System.currentTimeMillis(),
                        onReplay = { onPlayCatchup(programme) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(programme: EpgEntity, canReplay: Boolean, onReplay: () -> Unit) {
    val nowMillis = System.currentTimeMillis()
    val isLive = programme.start <= nowMillis && programme.stop > nowMillis
    FocusableSurface(
        onClick = { if (canReplay) onReplay() },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        focusedColor = KanalColors.SurfaceVariant,
        focusedScale = 1.0f,
        enabled = canReplay
    ) { isFocused ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatClock(programme.start),
                style = MaterialTheme.typography.labelMedium,
                color = if (isLive) KanalColors.Live else KanalColors.OnSurfaceFaint,
                modifier = Modifier.width(56.dp)
            )
            Text(
                programme.title,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    isFocused -> KanalColors.Accent
                    isLive -> KanalColors.OnBackground
                    else -> KanalColors.OnSurfaceMuted
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (canReplay) {
                Icon(
                    Icons.Outlined.Replay,
                    contentDescription = "Ver repetición",
                    tint = if (isFocused) KanalColors.Accent else KanalColors.OnSurfaceFaint,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun progressOf(programme: EpgEntity): Float {
    val total = (programme.stop - programme.start).toFloat()
    if (total <= 0f) return 0f
    return ((System.currentTimeMillis() - programme.start) / total).coerceIn(0f, 1f)
}
