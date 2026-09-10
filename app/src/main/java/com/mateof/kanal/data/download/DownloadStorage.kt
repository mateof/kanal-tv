package com.mateof.kanal.data.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import com.mateof.kanal.core.log.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Where a file being written lives, and how much of it is already there. */
data class DownloadTarget(val uri: String, val bytes: Long)

/**
 * Puts the files in `Downloads/Kanal`, which is the one place the user can
 * still find them with any file manager.
 *
 * Two ways in, because the platform changed under this feature: from API 29 the
 * only way into a shared folder is MediaStore, and below it a plain file plus a
 * scan so the gallery notices. The older path needs WRITE_EXTERNAL_STORAGE,
 * which the manifest already asks for with `maxSdkVersion="28"`.
 */
@Singleton
class DownloadStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: FileLogger
) {
    private val scoped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Opens (or reopens) the file for an item, ready to be appended to. */
    fun open(item: DownloadItem): DownloadTarget {
        if (item.target.isNotBlank()) {
            val existing = sizeOf(item.target)
            if (existing >= 0) return DownloadTarget(item.target, existing)
            // The user deleted it behind our back; start again rather than fail.
            logger.w("Descargas", "El fichero de '${item.title}' ya no está, se empieza de cero")
        }
        return if (scoped) createScoped(item) else createLegacy(item)
    }

    private fun createScoped(item: DownloadItem): DownloadTarget {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, item.fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(item.fileName))
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            // Hidden from other apps until it is whole: a half-written film in
            // the gallery is worse than no film at all.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver
            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("El sistema no dejó crear el fichero en Descargas.")
        return DownloadTarget(uri.toString(), 0L)
    }

    private fun createLegacy(item: DownloadItem): DownloadTarget {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FOLDER
        )
        if (!dir.exists() && !dir.mkdirs()) error("No se pudo crear la carpeta $FOLDER.")
        val file = File(dir, item.fileName)
        return DownloadTarget(Uri.fromFile(file).toString(), if (file.exists()) file.length() else 0L)
    }

    /** Append mode: a resumed download carries on where the last one stopped. */
    fun sink(target: String, append: Boolean): OutputStream {
        val uri = target.toUri()
        return if (uri.scheme == "content") {
            context.contentResolver.openOutputStream(uri, if (append) "wa" else "w")
                ?: error("No se pudo escribir en el fichero.")
        } else {
            // Not File.outputStream(): that truncates, and truncating here
            // would throw away the part already downloaded.
            java.io.FileOutputStream(File(uri.path!!), append)
        }
    }

    /** Makes the file visible to the rest of the system. */
    fun finish(target: String) {
        val uri = target.toUri()
        if (uri.scheme == "content") {
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            runCatching { context.contentResolver.update(uri, values, null, null) }
                .onFailure { logger.w("Descargas", "No se pudo publicar el fichero", it) }
        } else {
            MediaScannerConnection.scanFile(context, arrayOf(uri.path), null, null)
        }
    }

    fun sizeOf(target: String): Long {
        val uri = runCatching { target.toUri() }.getOrNull() ?: return -1
        return if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")
                    ?.use { it.statSize } ?: -1
            }.getOrDefault(-1)
        } else {
            val file = File(uri.path ?: return -1)
            if (file.exists()) file.length() else -1
        }
    }

    /**
     * Removes the file. After a reinstall the app no longer owns what it wrote,
     * and the system refuses; the entry is dropped from the list either way so
     * the screen does not keep offering something it cannot do.
     */
    fun delete(target: String): Boolean {
        if (target.isBlank()) return true
        val uri = runCatching { target.toUri() }.getOrNull() ?: return true
        return runCatching {
            if (uri.scheme == "content") {
                context.contentResolver.delete(uri, null, null) > 0
            } else {
                File(uri.path!!).delete()
            }
        }.getOrElse {
            logger.w("Descargas", "No se pudo borrar el fichero: ${it.message}")
            false
        }
    }

    /** Room left on the volume the downloads go to. */
    fun freeBytes(): Long = runCatching {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.usableSpace
    }.getOrDefault(Long.MAX_VALUE)

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2t"
        else -> "video/*"
    }

    private companion object {
        const val FOLDER = "Kanal"
    }
}

/** A file name that survives every file system: no separators, no surprises. */
fun safeFileName(title: String, extension: String): String {
    val clean = title.trim()
        .replace(Regex("[\\/:*?\"<>|]"), " ")
        .replace(Regex("\\s+"), " ")
        .take(90)
        .trim()
        .ifBlank { "Kanal" }
    // The extension often arrives as the tail of a url — `mp4?m=1` and worse —
    // and a file called ".mp4_m" opens with nothing. Only letters and digits
    // survive, and mp4 is the assumption when nothing does.
    val ext = extension.substringAfterLast('.', extension)
        .substringBefore('?')
        .substringBefore('#')
        .filter { it.isLetterOrDigit() }
        .lowercase()
        .take(5)
        .ifBlank { "mp4" }
    return "$clean.$ext"
}
