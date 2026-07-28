package com.mateof.kanal.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mateof.kanal.BuildConfig
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.net.HttpProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class GhRelease(
    val tag_name: String? = null,
    val name: String? = null,
    val body: String? = null,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList()
)

@Serializable
private data class GhAsset(
    val name: String? = null,
    val browser_download_url: String? = null,
    val size: Long = 0
)

data class UpdateInfo(
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long
)

sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    data class Available(val info: UpdateInfo) : UpdateCheck
    data class Error(val message: String) : UpdateCheck
}

/**
 * Looks for a newer APK in GitHub Releases, downloads it and hands it to the
 * system installer. The repo is public, so no token is involved.
 */
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: HttpProvider,
    private val logger: FileLogger
) {
    private val json = Json { ignoreUnknownKeys = true }

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "kanal-tv")
                .build()
            http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateCheck.Error("GitHub respondió ${response.code}")
                }
                val body = response.body?.string()
                    ?: return@withContext UpdateCheck.Error("Respuesta vacía")
                val release = json.decodeFromString<GhRelease>(body)
                val tag = release.tag_name?.removePrefix("v")?.trim().orEmpty()
                val apk = release.assets.firstOrNull {
                    it.name?.endsWith(".apk", ignoreCase = true) == true
                }
                if (tag.isBlank() || apk?.browser_download_url == null) {
                    return@withContext UpdateCheck.Error("La release no trae APK")
                }
                if (!isNewer(tag, currentVersion)) {
                    logger.d("Update", "Ya estás en la última versión ($currentVersion)")
                    return@withContext UpdateCheck.UpToDate
                }
                logger.i("Update", "Hay una versión nueva: $tag (tienes $currentVersion)")
                UpdateCheck.Available(
                    UpdateInfo(
                        versionName = tag,
                        notes = release.body?.trim().orEmpty(),
                        apkUrl = apk.browser_download_url,
                        apkSize = apk.size
                    )
                )
            }
        } catch (e: Exception) {
            logger.w("Update", "No se pudo comprobar si hay actualizaciones", e)
            UpdateCheck.Error(e.message ?: "Error comprobando actualizaciones")
        }
    }

    /** Downloads the APK reporting 0..100, or -1 when the size is unknown. */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "kanal-${info.versionName}.apk")
                val request = Request.Builder()
                    .url(info.apkUrl)
                    .header("User-Agent", "kanal-tv")
                    .build()
                http.longRunningClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        logger.w("Update", "La descarga respondió ${response.code}")
                        return@withContext null
                    }
                    val stream = response.body?.byteStream() ?: return@withContext null
                    val total = response.body?.contentLength() ?: -1L
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var downloaded = 0L
                        var lastPct = -1
                        while (stream.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = ((downloaded * 100) / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            } else {
                                onProgress(-1)
                            }
                        }
                    }
                }
                logger.i("Update", "APK descargado en ${file.absolutePath}")
                file
            } catch (e: Exception) {
                logger.e("Update", "Fallo descargando la actualización", e)
                null
            }
        }

    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** @return false when the device has no settings screen for unknown sources. */
    fun requestInstallPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /** Small semver-ish comparison: true when [remote] is greater than [local]. */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".", "-").mapNotNull { it.toIntOrNull() }
        val l = local.split(".", "-").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private companion object {
        const val REPO = "mateof/kanal-tv"
    }
}
