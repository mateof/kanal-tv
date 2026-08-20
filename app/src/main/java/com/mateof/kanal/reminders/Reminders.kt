package com.mateof.kanal.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mateof.kanal.MainActivity
import com.mateof.kanal.R
import com.mateof.kanal.core.log.FileLogger
import com.mateof.kanal.data.prefs.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/** A programme somebody asked to be told about. */
@Serializable
data class Reminder(
    val channelId: String,
    val channelName: String,
    val title: String,
    val startMillis: Long
) {
    /** Stable across restarts, which is what the alarm has to be keyed on. */
    val id: Int get() = (channelId + title + startMillis).hashCode()
}

/** How long before it starts the notice arrives. */
private const val LEAD_MS = 60_000L

private const val CHANNEL_ID = "programme_reminders"

/**
 * Tells the user a programme is about to start.
 *
 * The guide is already downloaded and until now could only be read. This is the
 * one thing it is actually for: being told, without having to remember.
 *
 * Deliberately not an exact alarm. Exactness needs a permission the user has to
 * grant by hand on recent Androids, and a minute either way is neither here nor
 * there for a television programme — the notice is sent a minute early anyway.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val logger: FileLogger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun add(reminder: Reminder) {
        prefs.addReminder(reminder)
        schedule(reminder)
        logger.i("Reminders", "Aviso para '${reminder.title}' en ${reminder.channelName}")
    }

    suspend fun remove(reminder: Reminder) {
        prefs.removeReminder(reminder)
        alarms()?.cancel(pendingFor(reminder))
        logger.i("Reminders", "Aviso retirado de '${reminder.title}'")
    }

    /** Re-arms everything still in the future, after a restart or a reboot. */
    fun restoreAll() = scope.launch {
        val now = System.currentTimeMillis()
        val live = prefs.reminders.first().filter { it.startMillis > now }
        live.forEach(::schedule)
        if (live.isNotEmpty()) logger.i("Reminders", "Avisos rearmados: ${live.size}")
    }

    private fun schedule(reminder: Reminder) {
        val manager = alarms() ?: return
        val at = (reminder.startMillis - LEAD_MS).coerceAtLeast(System.currentTimeMillis() + 1_000)
        runCatching {
            manager.setWindow(
                AlarmManager.RTC_WAKEUP,
                at,
                30_000L,
                pendingFor(reminder)
            )
        }.onFailure { logger.w("Reminders", "No se pudo programar el aviso", it) }
    }

    private fun alarms(): AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private fun pendingFor(reminder: Reminder): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_CHANNEL_ID, reminder.channelId)
            putExtra(EXTRA_CHANNEL_NAME, reminder.channelName)
            putExtra(EXTRA_TITLE, reminder.title)
        }
        return PendingIntent.getBroadcast(
            context,
            reminder.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channelId"
        const val EXTRA_CHANNEL_NAME = "channelName"
        const val EXTRA_TITLE = "title"
    }
}

/** Puts the notice on screen, and opens the channel when it is tapped. */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var logger: FileLogger

    override fun onReceive(context: Context, intent: Intent) {
        val channelId = intent.getStringExtra(ReminderScheduler.EXTRA_CHANNEL_ID).orEmpty()
        val channelName = intent.getStringExtra(ReminderScheduler.EXTRA_CHANNEL_NAME).orEmpty()
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE).orEmpty()
        if (title.isEmpty()) return

        ensureChannel(context)
        if (!allowed(context)) {
            logger.w("Reminders", "Sin permiso para avisar de '$title'")
            return
        }

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ReminderScheduler.EXTRA_CHANNEL_ID, channelId)
        }
        val pending = PendingIntent.getActivity(
            context,
            title.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notice = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kanal_mark)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.reminder_starting, channelName))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(title.hashCode(), notice)
        logger.i("Reminders", "Avisando de '$title' en $channelName")
    }

    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }
}

/** Alarms do not survive a reboot; the list of reminders does. */
@AndroidEntryPoint
class ReminderBootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        scheduler.restoreAll()
    }
}
