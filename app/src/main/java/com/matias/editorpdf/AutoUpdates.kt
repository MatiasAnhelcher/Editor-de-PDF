package com.matias.editorpdf

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * Búsqueda automática de versiones nuevas: dos veces por día, aunque la app esté cerrada.
 * Si encuentra una, avisa con una notificación (una sola vez por versión).
 */
object AutoUpdates {

    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 43
    const val ACTION_CHECK = "com.matias.editorpdf.CHECK_UPDATES"
    const val EXTRA_SHOW_UPDATE = "show_update"

    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean("auto", true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("auto", on).apply()
        schedule(context)
    }

    /** Momento de la última búsqueda (de cualquier tipo), en milisegundos; 0 si nunca. */
    fun lastCheck(context: Context): Long = prefs(context).getLong("last_check", 0)

    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (!isEnabled(context)) {
            alarms.cancel(pending(context))
            return
        }
        // Alarma inexacta que se repite: Android la agrupa con otras y casi no gasta batería.
        alarms.setInexactRepeating(
            AlarmManager.RTC,
            System.currentTimeMillis() + AlarmManager.INTERVAL_HOUR,
            AlarmManager.INTERVAL_HALF_DAY,
            pending(context),
        )
    }

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 1,
            Intent(context, UpdateCheckReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun checkInBackground(context: Context, done: () -> Unit) {
        val app = context.applicationContext
        thread(name = "update-check") {
            try {
                if (isEnabled(app)) {
                    val release = runCatching { runBlocking { Updater(app).check() } }.getOrNull()
                    if (release != null) notifyOnce(app, release)
                }
            } finally {
                done()
            }
        }
    }

    private fun notifyOnce(context: Context, r: Updater.Release) {
        val p = prefs(context)
        if (p.getLong("notified_code", 0) >= r.versionCode || !Reminders.canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.updates_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SHOW_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = context.getString(R.string.update_notify_title, r.name)
        val notes = r.notes.trim().lineSequence().firstOrNull()?.trim()
            ?.takeUnless { it.isEmpty() || it.startsWith("Descarga") }
            ?: context.getString(R.string.update_notify_text)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(ContextCompat.getColor(context, R.color.splash_bg))
            .setContentTitle(title)
            .setContentText(notes)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notes))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n) }
        p.edit().putLong("notified_code", r.versionCode).apply()
    }
}

class UpdateCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AutoUpdates.ACTION_CHECK) return
        val result = goAsync()
        AutoUpdates.checkInBackground(context) { result.finish() }
    }
}
