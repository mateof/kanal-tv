package com.mateof.kanal.ui.screens.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.kanal.data.download.DownloadItem
import com.mateof.kanal.data.download.DownloadRepository
import com.mateof.kanal.data.download.DownloadState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DownloadsState(
    val active: List<DownloadItem> = emptyList(),
    val ready: List<DownloadItem> = emptyList(),
    val usedBytes: Long = 0L
) {
    val isEmpty: Boolean get() = active.isEmpty() && ready.isEmpty()
}

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloads: DownloadRepository
) : ViewModel() {

    val state: StateFlow<DownloadsState> = downloads.downloads
        .map { all ->
            DownloadsState(
                // Oldest first while they are queued, so the order on screen is
                // the order they will actually happen in.
                active = all.filter { it.isActive || it.state == DownloadState.FAILED }
                    .sortedBy { it.createdAt },
                ready = all.filter { it.isDone }.sortedByDescending { it.createdAt },
                usedBytes = all.sumOf { it.bytes }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsState())

    fun remove(id: String) = viewModelScope.launch { downloads.remove(id) }

    fun retry(id: String) = viewModelScope.launch { downloads.retry(id) }

    /** Stops the transfer, keeping the part already written and its place in the queue. */
    fun stop(id: String) = viewModelScope.launch { downloads.pause(id) }

    fun removeSeries(seriesId: String, sourceId: String) =
        viewModelScope.launch { downloads.removeSeries(seriesId, sourceId) }
}
