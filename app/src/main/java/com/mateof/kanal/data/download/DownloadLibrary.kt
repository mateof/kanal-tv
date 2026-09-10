package com.mateof.kanal.data.download

import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.data.prefs.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers one question — "is this already on the device?" — for whoever needs
 * it. Kept apart from the repository that writes downloads so that playback can
 * ask without the two ending up depending on each other.
 */
@Singleton
class DownloadLibrary @Inject constructor(
    private val prefs: AppPreferences,
    private val storage: DownloadStorage
) {
    /**
     * The local file for an item, or null. Checks the file is really there:
     * the user can empty their Downloads folder from any file manager, and
     * playing a ghost would fail with something unhelpful.
     */
    suspend fun localUri(kind: ContentKind, sourceId: String, itemId: String): String? {
        val item = prefs.downloadsNow()
            .firstOrNull { it.id == downloadId(kind, sourceId, itemId) && it.isDone }
            ?: return null
        return item.target.takeIf { it.isNotBlank() && storage.sizeOf(it) > 0 }
    }
}
