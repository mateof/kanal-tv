package com.mateof.kanal.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mateof.kanal.MainActivity
import com.mateof.kanal.R
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.model.ContentKind
import com.mateof.kanal.data.net.HttpProvider
import com.mateof.kanal.data.prefs.AppPreferences
import com.mateof.kanal.data.repo.ContentRepository
import com.mateof.kanal.data.repo.PlaybackRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Downloads one file at a time, in the foreground.
 *
 * In the foreground because there is no other way: from Android 8 a background
 * job is killed long before a film is finished. One at a time on purpose as
 * well — every download holds one of the provider account's connections for as
 * long as it lasts, and those are counted.
 */
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var prefs: AppPreferences
    @Inject lateinit var http: HttpProvider
    @Inject lateinit var storage: DownloadStorage
    @Inject lateinit var content: ContentRepository
    @Inject lateinit var playback: PlaybackRepository
    @Inject lateinit var logger: FileLogger

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pump: Job? = null

    @Volatile
    private var cancelled: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) cancelled = intent.getStringExtra(EXTRA_ID)
        startForeground(NOTIFICATION_ID, notification(getString(R.string.downloads_title), null, 0f))
        if (pump?.isActive != true) pump = scope.launch { drain() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun drain() {
        while (true) {
            val next = prefs.downloadsNow()
                .filter { it.state == DownloadState.QUEUED || it.state == DownloadState.WAITING }
                .minByOrNull { it.createdAt } ?: break

            if (prefs.settings.first().downloadWifiOnly && !unmetered()) {
                // Parked rather than failed: it should carry on by itself once
                // there is Wi-Fi, which is what the setting promises.
                prefs.upsertDownload(next.copy(state = DownloadState.WAITING))
                notify(getString(R.string.downloads_waiting_wifi), null, 0f)
                delay(WIFI_POLL_MS)
                continue
            }

            runCatching { transfer(next) }.onFailure { error ->
                logger.w("Descargas", "'${next.title}' falló: ${error.message}")
                prefs.downloadsNow().firstOrNull { it.id == next.id }?.let { current ->
                    prefs.upsertDownload(
                        current.copy(
                            state = DownloadState.FAILED,
                            error = error.message.orEmpty().take(160)
                        )
                    )
                }
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun transfer(queued: DownloadItem) {
        val url = remoteUrl(queued) ?: error("El catálogo ya no tiene este contenido.")
        val target = storage.open(queued)
        var item = queued.copy(
            state = DownloadState.RUNNING,
            target = target.uri,
            bytes = target.bytes,
            error = ""
        )
        prefs.upsertDownload(item)
        notify(queued.title, queued.subtitle, item.progress)

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent(queued.sourceId))
            .apply { if (target.bytes > 0) header("Range", "bytes=${target.bytes}-") }
            .build()

        http.longRunningClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("El servidor respondió ${response.code}.")
            val body = response.body ?: throw IOException("Respuesta vacía.")

            // A server that ignores the Range header answers 200 with the whole
            // file: keeping the bytes already written would splice two copies of
            // the film together, so that case starts over.
            val resuming = response.code == 206 && target.bytes > 0
            var written = if (resuming) target.bytes else 0L
            val declared = body.contentLength()
            val total = if (declared > 0) written + declared else 0L
            item = item.copy(bytes = written, total = total)
            prefs.upsertDownload(item)

            val buffer = ByteArray(BUFFER_BYTES)
            var lastReport = 0L
            storage.sink(target.uri, append = resuming).use { out ->
                body.byteStream().use { input ->
                    while (true) {
                        if (cancelled == queued.id) {
                            cancelled = null
                            logger.i("Descargas", "'${queued.title}' cancelada")
                            prefs.upsertDownload(item.copy(bytes = written, state = DownloadState.QUEUED))
                            return
                        }
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport > REPORT_MS) {
                            lastReport = now
                            item = item.copy(bytes = written)
                            prefs.upsertDownload(item)
                            notify(queued.title, queued.subtitle, item.progress)
                        }
                    }
                }
            }
            storage.finish(target.uri)
            prefs.upsertDownload(
                item.copy(
                    bytes = written,
                    total = if (total > 0) total else written,
                    state = DownloadState.DONE
                )
            )
            logger.i("Descargas", "'${queued.title}' lista")
        }
    }

    /**
     * Rebuilt from the catalogue every time rather than stored with the item: a
     * provider url carries the account's credentials, and those change.
     */
    private suspend fun remoteUrl(item: DownloadItem): String? {
        val source = prefs.sources.first().firstOrNull { it.id == item.sourceId } ?: return null
        return when (item.kind) {
            ContentKind.MOVIE -> content.movie(item.sourceId, item.itemId)
                ?.let { playback.remoteMovieUrl(source, it) }

            ContentKind.SERIES -> content.episode(item.sourceId, item.itemId)
                ?.let { playback.remoteEpisodeUrl(source, it) }

            ContentKind.LIVE -> null
        }
    }

    private suspend fun userAgent(sourceId: String): String {
        val source = prefs.sources.first().firstOrNull { it.id == sourceId }
        return source?.userAgent?.takeIf { it.isNotBlank() } ?: prefs.settings.first().userAgent
    }

    private fun unmetered(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun notify(title: String, text: String?, progress: Float) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, notification(title, text, progress)) }
    }

    private fun notification(title: String, text: String?, progress: Float): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(100, (progress * 100).roundToInt(), progress <= 0f)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.downloads_title),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        private const val CHANNEL_ID = "kanal_downloads"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_CANCEL = "com.mateof.kanal.DOWNLOAD_CANCEL"
        private const val EXTRA_ID = "id"
        private const val REPORT_MS = 1_200L
        private const val WIFI_POLL_MS = 20_000L
        private const val BUFFER_BYTES = 256 * 1024

        /** Asks the running transfer to stop; it stays in the queue as paused. */
        fun cancel(context: Context, id: String) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_ID, id)
            runCatching { context.startService(intent) }
        }
    }
}
