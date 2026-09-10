package com.mateof.kanal.ui.screens.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mateof.kanal.R
import com.mateof.kanal.data.download.DownloadItem
import com.mateof.kanal.data.download.DownloadState
import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.ui.components.ActionMenu
import com.mateof.kanal.ui.components.ArtworkImage
import com.mateof.kanal.ui.components.FocusableSurface
import com.mateof.kanal.ui.components.MenuAction
import com.mateof.kanal.ui.components.MessageState
import com.mateof.kanal.ui.components.SectionHeader
import com.mateof.kanal.ui.components.ThinProgress
import com.mateof.kanal.ui.contentInset
import com.mateof.kanal.ui.isCompact
import com.mateof.kanal.ui.theme.KanalColors

/**
 * What is on the device, and what is on its way there.
 *
 * Playing from here goes through the normal player route: the item is the same
 * one the catalogue knows, and playback picks the local file up by itself.
 */
@Composable
fun DownloadsScreen(onPlay: (String, String) -> Unit) {
    val vm: DownloadsViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var acting by remember { mutableStateOf<DownloadItem?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = contentInset, end = contentInset, top = 20.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.downloads_title),
                    style = if (isCompact) {
                        MaterialTheme.typography.headlineSmall
                    } else {
                        MaterialTheme.typography.headlineMedium
                    },
                    color = KanalColors.OnBackground
                )
                if (!state.isEmpty) {
                    Text(
                        stringResource(R.string.downloads_used, formatBytes(state.usedBytes)),
                        style = MaterialTheme.typography.labelMedium,
                        color = KanalColors.OnSurfaceFaint
                    )
                }
            }
        }

        if (state.isEmpty) {
            MessageState(
                title = stringResource(R.string.downloads_empty_title),
                description = stringResource(R.string.downloads_empty_body),
                icon = Icons.Outlined.Download
            )
            return
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = contentInset,
                end = contentInset,
                top = 8.dp,
                bottom = 60.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.active.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.downloads_in_progress)) }
                items(state.active, key = { it.id }) { item ->
                    DownloadRow(item, onClick = { acting = item })
                }
            }
            if (state.ready.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(6.dp))
                    SectionHeader(stringResource(R.string.downloads_ready))
                }
                items(state.ready, key = { it.id }) { item ->
                    DownloadRow(
                        item,
                        onClick = { onPlay(item.kind.name, item.itemId) },
                        onLongClick = { acting = item }
                    )
                }
            }
        }
    }

    acting?.let { item ->
        ActionMenu(
            title = item.title,
            subtitle = item.subtitle,
            onDismiss = { acting = null },
            actions = buildList {
                if (item.isDone) {
                    add(
                        MenuAction(stringResource(R.string.detail_play), Icons.Outlined.PlayArrow) {
                            onPlay(item.kind.name, item.itemId)
                        }
                    )
                }
                if (item.state == DownloadState.FAILED) {
                    add(
                        MenuAction(stringResource(R.string.download_retry), Icons.Outlined.Refresh) {
                            vm.retry(item.id)
                        }
                    )
                }
                if (item.state == DownloadState.RUNNING) {
                    add(
                        MenuAction(stringResource(R.string.download_pause), Icons.Outlined.Pause) {
                            vm.stop(item.id)
                        }
                    )
                }
                add(
                    MenuAction(stringResource(R.string.download_delete), Icons.Outlined.Delete) {
                        vm.remove(item.id)
                    }
                )
                if (item.kind == ContentKind.SERIES && item.seriesId.isNotBlank()) {
                    add(
                        MenuAction(
                            stringResource(R.string.download_delete_series),
                            Icons.Outlined.Delete
                        ) { vm.removeSeries(item.seriesId, item.sourceId) }
                    )
                }
            }
        )
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    FocusableSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = KanalColors.Surface,
        focusedColor = KanalColors.SurfaceVariant,
        focusedScale = 1.01f
    ) { focused ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .width(74.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(KanalColors.BackgroundElevated)
            ) {
                ArtworkImage(item.cover, item.title, Icons.Outlined.Movie)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (focused) KanalColors.Accent else KanalColors.OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.subtitle.isNotBlank()) {
                    Text(
                        item.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = KanalColors.OnSurfaceFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = statusLine(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.state == DownloadState.FAILED) {
                        KanalColors.Error
                    } else {
                        KanalColors.OnSurfaceMuted
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.state == DownloadState.RUNNING && item.total > 0) {
                    Spacer(Modifier.height(6.dp))
                    ThinProgress(item.progress, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun statusLine(item: DownloadItem): String = when (item.state) {
    DownloadState.QUEUED -> stringResource(R.string.download_state_queued)
    DownloadState.WAITING -> stringResource(R.string.downloads_waiting_wifi)
    DownloadState.RUNNING -> if (item.total > 0) {
        "${(item.progress * 100).toInt()}%  ·  ${formatBytes(item.bytes)} / ${formatBytes(item.total)}"
    } else {
        "${stringResource(R.string.download_state_running)}  ·  ${formatBytes(item.bytes)}"
    }

    DownloadState.DONE -> formatBytes(item.bytes)
    DownloadState.FAILED ->
        "${stringResource(R.string.download_state_failed)}: ${item.error}".trim(':', ' ')
}

/** Sizes as a person reads them, not as the file system stores them. */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val gb = bytes / (1024.0 * 1024 * 1024)
    if (gb >= 1) return "%.1f GB".format(gb)
    return "%.0f MB".format(bytes / (1024.0 * 1024))
}
