package com.mateof.kanal.data.download

import com.mateof.kanal.data.model.ContentKind
import kotlinx.serialization.Serializable

enum class DownloadState {
    /** Waiting its turn: one at a time, to be kind to the provider's slots. */
    QUEUED,

    /** Held back by the Wi-Fi-only setting until there is Wi-Fi again. */
    WAITING,
    RUNNING,
    DONE,
    FAILED
}

/**
 * One file the user asked to keep. Lives in DataStore rather than Room: Room is
 * a cache here and gets dropped on a schema change, which would leave the files
 * on disk with nothing in the app knowing about them.
 */
@Serializable
data class DownloadItem(
    val id: String,
    val sourceId: String,
    val kind: ContentKind,
    val itemId: String,
    val title: String,
    val subtitle: String = "",
    val cover: String = "",
    /** Series only, so a whole show can be grouped and removed together. */
    val seriesId: String = "",
    val seriesName: String = "",
    val fileName: String = "",
    /** `content://` from API 29, an absolute path below it. Empty until created. */
    val target: String = "",
    val bytes: Long = 0L,
    val total: Long = 0L,
    val state: DownloadState = DownloadState.QUEUED,
    val error: String = "",
    val createdAt: Long = 0L
) {
    val progress: Float
        get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f

    val isDone: Boolean get() = state == DownloadState.DONE

    val isActive: Boolean
        get() = state == DownloadState.QUEUED || state == DownloadState.WAITING ||
            state == DownloadState.RUNNING
}

/** Stable key: the same thing downloaded from two sources is two files. */
fun downloadId(kind: ContentKind, sourceId: String, itemId: String) =
    "${kind.name}:$sourceId:$itemId"
