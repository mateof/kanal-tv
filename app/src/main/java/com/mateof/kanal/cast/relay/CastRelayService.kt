package com.mateof.kanal.cast.relay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mateof.kanal.MainActivity
import com.mateof.kanal.R
import com.mateof.kanal.cast.CastMonitor
import com.mateof.kanal.cast.UpnpClient
import com.mateof.kanal.core.UiText
import com.mateof.kanal.core.formatBitrate
import com.mateof.kanal.core.formatBytes
import com.mateof.kanal.core.log.FileLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the phone serving the television while the relay runs.
 *
 * In the foreground for the same reason as the downloads: nothing else survives
 * the screen going off for the length of a film. It also holds a Wi-Fi lock, or
 * the radio drops into power saving and the picture on the television stutters.
 *
 * It ends the relay when the television stops asking, stops answering, or the
 * phone loses the network the television is on. A provider that cannot be
 * reached ends it from inside the server, the moment a request fails.
 */
@AndroidEntryPoint
class CastRelayService : Service() {

    @Inject lateinit var relay: CastRelay
    @Inject lateinit var upnp: UpnpClient
    @Inject lateinit var monitor: CastMonitor
    @Inject lateinit var logger: FileLogger

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdog: Job? = null
    private var follower: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** The relay session this instance was started for; see [CastRelay.generation]. */
    @Volatile
    private var serving = -1

    @Volatile
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val running = relay.status.value as? RelayStatus.Running
        // Required promptly after startForegroundService, even when the answer
        // is to stop straight away.
        enterForeground(running)

        if (intent?.action == ACTION_STOP) {
            scope.launch {
                running?.let { upnp.stop(it.device) }
                relay.end(null, byUser = true)
            }
            return START_NOT_STICKY
        }
        if (running == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        serving = relay.generation
        holdLocks()
        watchNetwork(running)
        if (follower?.isActive != true) {
            follower = scope.launch {
                relay.status.collect { status ->
                    if (status is RelayStatus.Running) return@collect
                    // Not one about to be sent again by itself: the controller
                    // posts the notice if that fails too.
                    relay.lastEnded?.takeIf { it.problem != null && !it.recoverable }
                        ?.let { postEndNotice(this@CastRelayService, it) }
                    // A new send sets Running *before* starting this service
                    // again, so: read the id, then make sure nothing reopened.
                    // stopSelf(id) is ignored if a newer start has arrived since.
                    val id = lastStartId
                    if (relay.status.value !is RelayStatus.Running) stopSelf(id)
                }
            }
        }
        if (watchdog?.isActive != true) watchdog = scope.launch { watch() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        unwatchNetwork()
        scope.cancel()
        // Whatever stopped the service, nothing must keep listening behind it —
        // but a newer session belongs to a newer start, not to this one.
        relay.closeIfStill(serving, "servicio detenido")
        super.onDestroy()
    }

    private suspend fun watch() {
        var lastPoll = System.currentTimeMillis()
        var misses = 0
        var lastState: String? = null
        while (scope.isActive) {
            delay(TICK_MS)
            val running = relay.status.value as? RelayStatus.Running ?: return
            val idle = relay.idleMs() ?: return
            refreshNotification(running)
            if (idle > CUT_QUIET_MS && relay.cutAndAbandoned()) {
                // A television whose live stream was cut tends to stop rather
                // than ask again; waiting the full minute only lengthens the
                // black screen before sending it again.
                relay.end(
                    "el servidor cortó la emisión y la tele no volvió a pedirla",
                    recoverable = true
                )
                return
            }
            if (idle > IDLE_LIMIT_MS) {
                // Not a problem to report: usually the television was switched
                // off or told to play something else.
                logger.i("Cast", "La tele lleva ${idle / 1000} s sin pedir la emisión")
                relay.end(null)
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastPoll < POLL_MS) continue
            lastPoll = now
            upnp.transportState(running.device)
                .onSuccess { state ->
                    misses = 0
                    // Only changes: an evening of PLAYING every fifteen seconds
                    // would bury everything else in the log.
                    if (state != lastState) logger.d("Cast", "${running.device.name}: $state")
                    lastState = state
                    monitor.tvState(state)
                }
                .onFailure { error ->
                    misses++
                    logger.w("Cast", "${running.device.name} no contesta ($misses/$MAX_MISSES): ${error.message}")
                    monitor.tvSilent(misses, MAX_MISSES, error.message)
                    if (misses >= MAX_MISSES) {
                        relay.end("la tele dejó de responder", UiText(R.string.cast_hint_tv_gone))
                        return
                    }
                }
        }
    }

    private fun watchNetwork(running: RelayStatus.Running) {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                val current = relay.status.value as? RelayStatus.Running ?: return
                if (network == current.link.network) {
                    relay.end("se perdió la red local", UiText(R.string.cast_hint_lan_lost))
                }
            }
        }
        // Without the internet requirement the default request carries: the
        // television's network may well have no way out at all.
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { logger.w("Cast", "No se pudo vigilar la red local", it) }
        logger.d("Cast", "Vigilando ${running.link.interfaceName ?: "la red local"}")
    }

    private fun unwatchNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun holdLocks() {
        if (wifiLock == null) {
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "kanal:relay")
                ?.apply { setReferenceCounted(false); acquire() }
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kanal:relay")
                ?.apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        wifiLock = null
        wakeLock = null
    }

    private fun enterForeground(running: RelayStatus.Running?) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(running), type) }
            .onFailure { logger.w("Cast", "No se pudo poner el relay en primer plano", it) }
    }

    /** Speed and total in the notification, so a glance at the phone says it is working. */
    private fun refreshNotification(running: RelayStatus.Running) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, notification(running)) }
    }

    private fun notification(running: RelayStatus.Running?): Notification {
        ensureChannel(this)
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CastRelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val session = monitor.session.value?.takeIf { it.device == running?.device && it.servedBytes > 0 }
        val text = if (session != null) {
            getString(
                R.string.cast_relay_notification_stats,
                running?.title.orEmpty(),
                formatBitrate(session.rateBytes),
                formatBytes(session.servedBytes)
            )
        } else {
            getString(R.string.cast_relay_notification_text, running?.title.orEmpty())
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.cast_relay_notification_title, running?.device?.name.orEmpty()))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_kanal_mark)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openPanel(this))
            .addAction(0, getString(R.string.cast_relay_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "kanal_cast_relay"
        private const val NOTIFICATION_ID = 4712
        private const val NOTICE_ID = 4713
        private const val ACTION_STOP = "com.mateof.kanal.CAST_RELAY_STOP"
        private const val TICK_MS = 5_000L
        private const val POLL_MS = 15_000L
        private const val MAX_MISSES = 3

        /** Long enough for a television to reconnect after a hiccup or a channel change. */
        private const val IDLE_LIMIT_MS = 60_000L

        /** After the server cut the stream: a television that means to ask again does so at once. */
        private const val CUT_QUIET_MS = 8_000L

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, CastRelayService::class.java))
            }
        }

        /** Left behind after the service is gone, so a failure is not silent. */
        fun postEndNotice(context: Context, ended: RelayEnded) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            ensureChannel(context)
            val notice = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.cast_relay_stopped, ended.device.name))
                .setContentText(ended.problem)
                .setStyle(NotificationCompat.BigTextStyle().bigText(ended.problem))
                .setSmallIcon(R.drawable.ic_kanal_mark)
                .setAutoCancel(true)
                .setContentIntent(openPanel(context))
                .build()
            runCatching { manager.notify(NOTICE_ID, notice) }
        }

        /** A send that went out again makes the notice that it had stopped wrong. */
        fun clearEndNotice(context: Context) {
            runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTICE_ID) }
        }

        /** Opens the app on the transmission panel, where it can be followed and sent again. */
        private fun openPanel(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_CAST, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.cast_relay_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
