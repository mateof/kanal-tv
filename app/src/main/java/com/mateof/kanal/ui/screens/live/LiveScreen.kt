package com.mateof.kanal.ui.screens.live

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.res.stringResource
import com.mateof.kanal.R
import com.mateof.kanal.core.UiText
import com.mateof.kanal.core.resolve
import com.mateof.kanal.core.formatClock
import com.mateof.kanal.data.db.ChannelEntity
import com.mateof.kanal.data.db.EpgEntity
import com.mateof.kanal.ui.components.ArtworkImage
import com.mateof.kanal.ui.components.ChannelGuide
import com.mateof.kanal.ui.components.FocusableSurface
import com.mateof.kanal.ui.components.KanalButton
import com.mateof.kanal.ui.components.KanalChip
import com.mateof.kanal.ui.components.MessageState
import com.mateof.kanal.ui.components.ProgrammeDetail
import com.mateof.kanal.ui.components.ProgrammeDialog
import com.mateof.kanal.ui.components.ThinProgress
import com.mateof.kanal.ui.components.scrollingTitle
import com.mateof.kanal.ui.isCompact
import com.mateof.kanal.ui.theme.KanalColors

/** Two BACK presses closer than this count as "get me out of here". */
private const val DOUBLE_BACK_MS = 900L

@Composable
fun LiveScreen(
    onPlay: (String, Long) -> Unit,
    /** Channel just left behind in the player, or null when arriving fresh. */
    resumedChannelId: String? = null,
    onBack: () -> Unit = {}
) {
    val vm: LiveViewModel = hiltViewModel()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val selectedCategory by vm.selectedCategory.collectAsStateWithLifecycle()
    val focused by vm.focused.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val next by vm.next.collectAsStateWithLifecycle()
    val schedule by vm.schedule.collectAsStateWithLifecycle()
    val guideDays by vm.guideDays.collectAsStateWithLifecycle()
    val selectedDay by vm.selectedDay.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val previewActive by vm.previewActive.collectAsStateWithLifecycle()
    val previewError by vm.previewError.collectAsStateWithLifecycle()
    val favoriteChannels by vm.favoriteChannels.collectAsStateWithLifecycle()

    var detail by remember { mutableStateOf<ProgrammeDetail?>(null) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val paged = vm.channels.collectAsLazyPagingItems()

    // Coming back from the player: keep that channel sounding in the preview
    // instead of going silent, and remember it for the BACK shortcut below.
    var resumeTarget by remember { mutableStateOf<String?>(null) }
    var arrivedAt by remember { mutableLongStateOf(0L) }
    val compactLayout = isCompact
    LaunchedEffect(resumedChannelId) {
        val id = resumedChannelId ?: return@LaunchedEffect
        if (!settings.keepLastChannel) return@LaunchedEffect
        resumeTarget = id
        arrivedAt = System.currentTimeMillis()
        // Upright there is no preview pane to show it in, and starting the
        // player anyway would leave audio going with nothing on screen. The
        // BACK shortcut below still works there.
        if (!compactLayout) vm.resumeChannel(id)
    }

    // BACK returns to the channel full screen. Two quick presses — the one that
    // left the player and this one — mean "I want out", so leave instead.
    BackHandler(enabled = settings.keepLastChannel && resumeTarget != null && detail == null) {
        val target = resumeTarget
        if (System.currentTimeMillis() - arrivedAt < DOUBLE_BACK_MS || target == null) {
            onBack()
        } else {
            onPlay(target, 0L)
        }
    }
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

    Box(Modifier.fillMaxSize()) {
    // A 250 dp column of categories plus a 420 dp list of channels leaves the
    // preview and guide pane with almost nothing on a 960 dp television. The
    // categories move to a strip of chips across the top instead, which is also
    // what the guide wall does, so the two screens read the same.
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(start = 8.dp, end = 40.dp, top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                KanalChip(
                    label = stringResource(R.string.live_all_channels),
                    selected = selectedCategory == CATEGORY_ALL,
                    onClick = { vm.selectCategory(CATEGORY_ALL) }
                )
            }
            item {
                KanalChip(
                    label = stringResource(R.string.nav_favorites),
                    selected = showingFavorites,
                    onClick = { vm.selectCategory(CATEGORY_FAVORITES) }
                )
            }
            items(categories, key = { it.categoryId }) { category ->
                KanalChip(
                    label = category.name,
                    selected = category.categoryId == selectedCategory,
                    onClick = { vm.selectCategory(category.categoryId) }
                )
            }
        }
        Spacer(Modifier.height(10.dp))

    Row(Modifier.fillMaxSize()) {
        // --- Channels -------------------------------------------------------
        Box(
            Modifier
                .width(420.dp)
                .fillMaxHeight()
        ) {
            if (showingFavorites) {
                if (favoriteChannels.isEmpty()) {
                    MessageState(
                        title = stringResource(R.string.live_no_favorites),
                        description = stringResource(R.string.live_no_favorites_body),
                        icon = Icons.Filled.Star
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp, start = 6.dp, end = 6.dp),
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
                    contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp, start = 6.dp, end = 6.dp),
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
                                title = stringResource(R.string.live_no_channels),
                                description = stringResource(R.string.live_sync_hint),
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
            guideDays = guideDays,
            selectedDay = selectedDay,
            onSelectDay = vm::selectDay,
            previewActive = previewActive,
            previewError = previewError,
            player = vm.previewPlayer(),
            isFavorite = focused?.let { channel ->
                source?.let { favorites.contains("LIVE:${it.id}:${channel.streamId}") } == true
            } == true,
            onToggleFavorite = { focused?.let(vm::toggleFavorite) },
            onPlay = { focused?.let { onPlay(it.streamId, 0L) } },
            onProgrammeClick = { programme ->
                focused?.let { channel ->
                    detail = ProgrammeDetail(
                        programme = programme,
                        channelName = channel.name,
                        channelLogo = channel.logo,
                        channelStreamId = channel.streamId,
                        canReplay = channel.archiveDays > 0 &&
                            programme.stop < System.currentTimeMillis()
                    )
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        }
    }

    detail?.let { open ->
        ProgrammeDialog(
            detail = open,
            onDismiss = { detail = null },
            onReplay = if (open.canReplay) {
                {
                    val programme = open.programme
                    detail = null
                    onPlay(open.channelStreamId, programme.start)
                }
            } else {
                null
            }
        )
    }
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
            stringResource(R.string.nav_live),
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
                    label = stringResource(R.string.common_all_m),
                    selected = selectedCategory == CATEGORY_ALL,
                    onClick = { onSelectCategory(CATEGORY_ALL) }
                )
            }
            item {
                KanalChip(
                    label = stringResource(R.string.nav_favorites),
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
                    title = stringResource(R.string.live_no_favorites),
                    description = stringResource(R.string.live_no_favorites_body),
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
                            title = stringResource(R.string.live_no_channels),
                            description = stringResource(R.string.live_sync_hint),
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
                    text = now?.title ?: stringResource(R.string.common_no_guide),
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
                    contentDescription = stringResource(R.string.common_favorite),
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
    guideDays: List<Long>,
    selectedDay: Long,
    onSelectDay: (Long) -> Unit,
    previewActive: Boolean,
    previewError: UiText?,
    player: androidx.media3.exoplayer.ExoPlayer?,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit,
    onProgrammeClick: (EpgEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    if (channel == null) {
        MessageState(
            title = stringResource(R.string.live_pick_channel),
            description = stringResource(R.string.live_pick_channel_body),
            icon = Icons.Outlined.LiveTv,
            modifier = modifier
        )
        return
    }

    // Scrolls, and the guide below is height-capped rather than weighted. A plain
    // Column here silently crushed whatever did not fit: with a two-line
    // description the action buttons were laid out one pixel tall — present to
    // the remote and to a screen reader, invisible on screen.
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 44.dp, top = 4.dp, bottom = 28.dp)
    ) {
        // Capped on purpose: at 16:9 the preview eats the whole pane on a short
        // panel and leaves the guide underneath with no room at all.
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
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
                    previewError?.let { previewErrorText ->
                        Spacer(Modifier.height(12.dp))
                        Text(
                            previewErrorText.resolve(),
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
                if (channel.number > 0) append(stringResource(R.string.live_channel_number, channel.number))
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
                softWrap = false,
                modifier = Modifier.fillMaxWidth().scrollingTitle()
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
                stringResource(R.string.live_next_up, formatClock(next.start), next.title),
                style = MaterialTheme.typography.bodySmall,
                color = KanalColors.OnSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KanalButton(
                text = stringResource(R.string.live_watch_now),
                onClick = onPlay,
                tone = com.mateof.kanal.ui.components.ButtonTone.Primary
            )
            KanalButton(
                text = if (isFavorite) stringResource(R.string.live_remove_favorite) else stringResource(R.string.live_add_favorite),
                onClick = onToggleFavorite,
                icon = Icons.Filled.Star
            )
        }

        if (schedule.isNotEmpty() || guideDays.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Text(
                stringResource(R.string.live_schedule),
                style = MaterialTheme.typography.titleSmall,
                color = KanalColors.OnSurfaceMuted
            )
            Spacer(Modifier.height(10.dp))
            ChannelGuide(
                days = guideDays,
                selectedDay = selectedDay,
                programmes = schedule,
                // Capped, not weighted: weight means nothing inside a scrolling
                // column, and the lazy list needs a bounded height to measure.
                modifier = Modifier.heightIn(max = 340.dp),
                archiveAvailable = channel.archiveDays > 0,
                onSelectDay = onSelectDay,
                onProgrammeClick = onProgrammeClick
            )
        }
    }
}

private fun progressOf(programme: EpgEntity): Float {
    val total = (programme.stop - programme.start).toFloat()
    if (total <= 0f) return 0f
    return ((System.currentTimeMillis() - programme.start) / total).coerceIn(0f, 1f)
}
