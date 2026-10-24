package com.mateof.kanal.cast.relay

import com.mateof.kanal.core.resolve
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
import com.mateof.kanal.cast.CastController
import com.mateof.kanal.cast.CastMonitor
import com.mateof.kanal.cast.CastPhase
import com.mateof.kanal.cast.CastSession
import com.mateof.kanal.cast.Renderers
import com.mateof.kanal.cast.tvStateRes
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * The notification of a send in progress, and whatever keeps it honest.
 *
 * Runs for every send, not only the relayed ones: the user wants to see at a
 * glance what is on the television and stop it from there. In the foreground
 * because nothing else survives the screen going off for the length of a film,
 * and because a foreground notification goes away with the process — a plain
 * ongoing one would outlive it and lie.
 *
 * Through the relay it also holds a Wi-Fi lock, or the radio drops into power
 * saving and the picture stutters, and ends the relay when the television
 * stops asking, stops answering, or the phone loses its network. Sent directly
 * there is no traffic to watch, so the television is asked what it is doing,
 * and the send ends when it stops playing or answering.
 */
@AndroidEntryPoint
class CastRelayService : Service() {

    @Inject lateinit var relay: CastRelay
    @Inject lateinit var renderers: Renderers
    @Inject lateinit var monitor: CastMonitor
    @Inject lateinit var controller: CastController
    @Inject lateinit var logger: FileLogger

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdog: Job? = null
    private var follower: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** The relay session this instance serves, if any; see [CastRelay.generation]. */
    @Volatile
    private var serving = -1

    @Volatile
    private var lastStartId = 0

    /** A reconnect asked for from the notification, still on its way. */
    private val reconnecting = AtomicBoolean(false)

    /** The end already told in a notice, so it is told once. */
    private var noticeFor: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Required promptly after startForegroundService, even when the answer
        // is to stop straight away.
        enterForeground()

        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    if (monitor.session.value != null) {
                        controller.stopCurrent()
                    } else {
                        (relay.status.value as? RelayStatus.Running)?.let { renderers.stop(it.device) }
                        relay.end(null, byUser = true)
                    }
                    stopIfIdle(startId)
                }
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                reconnecting.set(true)
                scope.launch {
                    try {
                        controller.reconnect()
                    } finally {
                        reconnecting.set(false)
                        stopIfIdle(lastStartId)
                    }
                }
            }
        }

        if (!wanted()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        adoptRelay()
        if (follower?.isActive != true) {
            follower = scope.launch {
                relay.status.combine(monitor.session) { _, session -> session }.collect { session ->
                    if (session?.phase == CastPhase.ENDED) noticeEnd(session)
                    refreshNotification()
                    stopIfIdle(lastStartId)
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
        if (serving >= 0) relay.closeIfStill(serving, "servicio detenido")
        super.onDestroy()
    }

    /**
     * Something to show: a relay running, or a send that is playing or being
     * sent again. One that ended leaves a notice instead.
     */
    private fun wanted(): Boolean {
        if (relay.status.value is RelayStatus.Running || reconnecting.get()) return true
        val phase = monitor.session.value?.phase ?: return false
        return phase == CastPhase.ACTIVE || phase == CastPhase.RECONNECTING
    }

    /**
     * Stops unless there is still something to show. A new send sets its
     * state *before* starting this service again, and stopSelf(id) is ignored
     * once a newer start has arrived, so a send replacing another survives it.
     */
    private fun stopIfIdle(startId: Int) {
        if (!wanted()) stopSelf(startId)
    }

    /** Takes on the relay that is running now, which may be newer than the one this started for. */
    private fun adoptRelay() {
        val running = relay.status.value as? RelayStatus.Running
        if (running == null) {
            releaseLocks()
            return
        }
        serving = relay.generation
        holdLocks()
        watchNetwork(running)
    }

    private suspend fun watch() {
        var lastPoll = 0L
        var misses = 0
        var lastState: String? = null
        var stoppedPolls = 0
        var sawPlaying = false
        var orphanTicks = 0
        // The send being watched: anything counted about the previous one starts over.
        var watching: Long? = null
        while (scope.isActive) {
            delay(TICK_MS)
            val session = monitor.session.value
            val running = relay.status.value as? RelayStatus.Running
            if (session?.connectedAt != watching) {
                watching = session?.connectedAt
                misses = 0
                lastState = null
                stoppedPolls = 0
                sawPlaying = false
                orphanTicks = 0
                lastPoll = System.currentTimeMillis()
            }
            refreshNotification()
            if (running != null) {
                // Started again from the background, a relay may not have
                // come through onStartCommand: take it on here.
                if (serving != relay.generation) adoptRelay()
                val idle = relay.idleMs() ?: continue
                if (idle > CUT_QUIET_MS && relay.cutAndAbandoned()) {
                    // A television whose live stream was cut tends to stop rather
                    // than ask again; waiting the full minute only lengthens the
                    // black screen before sending it again.
                    relay.end(
                        "el servidor cortó la emisión y la tele no volvió a pedirla",
                        recoverable = true
                    )
                    continue
                }
                // A paused television may hang up and ask again, with a range,
                // when it carries on: that silence is not it having gone.
                if (idle > IDLE_LIMIT_MS && session?.tvState != "PAUSED_PLAYBACK") {
                    // Not a problem to report: usually the television was switched
                    // off or told to play something else.
                    logger.i("Cast", "La tele lleva ${idle / 1000} s sin pedir la emisión")
                    relay.end(null)
                    continue
                }
            } else if (session == null || session.phase != CastPhase.ACTIVE) {
                continue
            } else if (session.viaPhone) {
                // Relayed, with no relay: the end is normally on its way to the
                // controller. If it never arrives the notification would stay
                // up for nothing, so after a while it is ended from here.
                if (++orphanTicks >= ORPHAN_TICKS) controller.endWatched(null, null)
                continue
            }
            val device = running?.device ?: session?.device ?: continue
            val now = System.currentTimeMillis()
            if (now - lastPoll < POLL_MS) continue
            lastPoll = now
            renderers.transportState(device)
                .onSuccess { state ->
                    misses = 0
                    // Only changes: an evening of PLAYING every fifteen seconds
                    // would bury everything else in the log.
                    if (state != lastState) logger.d("Cast", "${device.name}: $state")
                    lastState = state
                    monitor.tvState(state)
                    if (running != null || session == null) return@onSuccess
                    // Sent directly, the television's word is all there is.
                    when (state) {
                        "PLAYING" -> {
                            sawPlaying = true
                            stoppedPolls = 0
                        }
                        "STOPPED", "NO_MEDIA_PRESENT" -> {
                            stoppedPolls++
                            // webOS holds a send in TRANSITIONING for half a
                            // minute and may say STOPPED on the way in.
                            val settled = sawPlaying || now - session.connectedAt > DIRECT_GRACE_MS
                            if (stoppedPolls >= STOPPED_POLLS && settled) {
                                logger.i("Cast", "${device.name} ha dejado de reproducir ($state)")
                                controller.endWatched(null, null)
                            }
                        }
                        else -> stoppedPolls = 0
                    }
                }
                .onFailure { error ->
                    misses++
                    logger.w("Cast", "${device.name} no contesta ($misses/$MAX_MISSES): ${error.message}")
                    monitor.tvSilent(misses, MAX_MISSES, error.message)
                    if (misses >= MAX_MISSES) {
                        val problem = "la tele dejó de responder"
                        val hint = UiText(R.string.cast_hint_tv_gone)
                        if (running != null) relay.end(problem, hint) else controller.endWatched(problem, hint)
                    }
                }
        }
    }

    private fun noticeEnd(session: CastSession) {
        val problem = session.endProblem ?: return
        if (noticeFor == session.endedAt) return
        noticeFor = session.endedAt
        // The advice, in the language of the interface; what went wrong
        // underneath is written for the log and stays on the panel.
        postEndNotice(this, session.device.name, session.endHint?.resolve(this) ?: problem)
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

    private fun enterForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type) }
            .onFailure { logger.w("Cast", "No se pudo poner el envío en primer plano", it) }
    }

    /** Redrawn every tick, so a glance at the phone says what the television is doing. */
    private fun refreshNotification() {
        if (!wanted()) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, notification()) }
    }

    private fun notification(): Notification {
        ensureChannel(this)
        val running = relay.status.value as? RelayStatus.Running
        val session = monitor.session.value
        val device = session?.device?.name ?: running?.device?.name.orEmpty()
        val title = session?.request?.title ?: running?.title.orEmpty()
        val reconnecting = session?.phase == CastPhase.RECONNECTING

        val text = when {
            reconnecting && session != null && session.attempt > 0 -> getString(
                R.string.cast_notification_attempt,
                title,
                session.attempt,
                CastController.MAX_AUTO_ATTEMPTS
            )
            reconnecting -> title
            session != null && session.viaPhone && session.servedBytes > 0 -> getString(
                R.string.cast_relay_notification_stats,
                title,
                formatBitrate(session.rateBytes),
                formatBytes(session.servedBytes)
            )
            running != null || session?.viaPhone == true -> getString(R.string.cast_relay_notification_text, title)
            else -> getString(R.string.cast_notification_direct, title)
        }
        val state = session?.tvState?.takeIf { !reconnecting }?.let { raw ->
            tvStateRes(raw)?.let { getString(it) } ?: raw
        }
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CastRelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                getString(
                    if (reconnecting) R.string.cast_notification_reconnecting else R.string.cast_relay_notification_title,
                    device
                )
            )
            .setContentText(text)
            .setSubText(state)
            .setSmallIcon(R.drawable.ic_kanal_mark)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setContentIntent(openPanel(this))
            .addAction(0, getString(R.string.cast_action_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "kanal_cast_relay"
        private const val NOTIFICATION_ID = 4712
        private const val NOTICE_ID = 4713
        private const val ACTION_STOP = "com.mateof.kanal.CAST_RELAY_STOP"
        private const val ACTION_RECONNECT = "com.mateof.kanal.CAST_RECONNECT"
        private const val TICK_MS = 5_000L
        private const val POLL_MS = 15_000L
        private const val MAX_MISSES = 3

        /** Long enough for a television to reconnect after a hiccup or a channel change. */
        private const val IDLE_LIMIT_MS = 60_000L

        /** After the server cut the stream: a television that means to ask again does so at once. */
        private const val CUT_QUIET_MS = 8_000L

        /** A relayed send whose relay is gone, given this many ticks for the end to arrive. */
        private const val ORPHAN_TICKS = 3

        /** Sent directly: two polls in a row saying it stopped, so a blip does not end it. */
        private const val STOPPED_POLLS = 2

        /** Sent directly: how long a television may sit stopped before it is believed. */
        private const val DIRECT_GRACE_MS = 60_000L

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, CastRelayService::class.java))
            }
        }

        /** Left behind after the send is gone, so a failure is not silent, with a way to try again. */
        fun postEndNotice(context: Context, deviceName: String, text: String) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            ensureChannel(context)
            val intent = Intent(context, CastRelayService::class.java).setAction(ACTION_RECONNECT)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val reconnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(context, 2, intent, flags)
            } else {
                PendingIntent.getService(context, 2, intent, flags)
            }
            val notice = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.cast_relay_stopped, deviceName))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_kanal_mark)
                .setAutoCancel(true)
                .setContentIntent(openPanel(context))
                .addAction(0, context.getString(R.string.cast_action_reconnect), reconnect)
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
