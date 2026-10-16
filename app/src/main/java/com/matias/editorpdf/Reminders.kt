package com.matias.editorpdf

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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Recordatorio diario para no cortar la racha: avisa solo si ese día todavía no se cumplió la meta. */
object Reminders {

    private const val CHANNEL = "study_reminder"
    private const val NOTIFICATION_ID = 42
    const val ACTION_REMIND = "com.matias.editorpdf.REMIND"

    private fun prefs(context: Context) = context.getSharedPreferences("reminder", Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean("on", false)

    fun time(context: Context): LocalTime = prefs(context).let { LocalTime.of(it.getInt("h", 20), it.getInt("m", 0)) }

    fun enable(context: Context, at: LocalTime) {
        prefs(context).edit().putBoolean("on", true).putInt("h", at.hour).putInt("m", at.minute).apply()
        schedule(context)
    }

    fun disable(context: Context) {
        prefs(context).edit().putBoolean("on", false).apply()
        alarms(context).cancel(pending(context))
    }

    /** Programa el próximo aviso (hoy si todavía no pasó la hora, si no mañana). */
    fun schedule(context: Context) {
        if (!isEnabled(context)) return
        val now = LocalDateTime.now()
        var next = LocalDateTime.of(LocalDate.now(), time(context))
        if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
        val millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Alarma inexacta: no necesita permisos especiales y puede llegar unos minutos tarde.
        alarms(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending(context))
    }

    private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notifyIfNeeded(context: Context) {
        val stats = StudyStats(context)
        val today = stats.today()
        val goal = stats.goalMinutes
        if (today.minutes >= goal || !canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val streak = stats.streak()
        val title = if (streak > 0) {
            context.getString(R.string.reminder_title_streak, streak)
        } else {
            context.getString(R.string.reminder_title)
        }
        val text = if (today.minutes > 0) {
            context.getString(R.string.reminder_text_partial, goal - today.minutes)
        } else {
            context.getString(R.string.reminder_text, goal)
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(ContextCompat.getColor(context, R.color.splash_bg))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Reminders.ACTION_REMIND) Reminders.notifyIfNeeded(context)
        // Tras cada aviso, reinicio o actualización, se programa el siguiente.
        Reminders.schedule(context)
    }
}
