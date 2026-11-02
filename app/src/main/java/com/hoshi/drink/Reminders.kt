package com.hoshi.drink

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Reminders {
    private const val WORK = "drink-reminder"
    private const val ALARM_ACTION = "com.hoshi.drink.REMIND"
    private const val TOKEN = "token"
    private const val DUE = "due"
    private const val BACKUP_DELAY = 5 * 60 * 1000L

    private fun preferences(context: Context) = context.getSharedPreferences("reminder_schedule", Context.MODE_PRIVATE)

    fun permission(context: Context, style: ReminderStyle = currentSettings(context).reminderStyle): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(style.channelId)?.importance != NotificationManager.IMPORTANCE_NONE

    fun exactAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun currentSettings(context: Context) = DrinkRepository(context).use { it.settings() }

    fun createChannel(context: Context, style: ReminderStyle = currentSettings(context).reminderStyle) {
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val channel = NotificationChannel(style.channelId, "饮水提醒 · ${style.label}",
            if (style == ReminderStyle.SILENT) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "在设定时段内提醒补充水分"
            setSound(if (style.sound) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else null, attributes)
            if (style.vibrate) vibrationPattern = longArrayOf(0, 250, 120, 250)
            enableVibration(style.vibrate)
        }
        // Android owns channel preferences after creation; each mode has its own stable channel.
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun alarmIntent(context: Context, token: String) = PendingIntent.getBroadcast(context, 1002,
        Intent(context, ReminderReceiver::class.java).setAction(ALARM_ACTION).putExtra(TOKEN, token),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @Synchronized
    fun schedule(context: Context, settings: DrinkSettings, drinks: List<Drink>, fromWorker: Boolean = false, reset: Boolean = true) {
        val work = WorkManager.getInstance(context)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val prefs = preferences(context)
        if (!settings.enabled) {
            prefs.edit().clear().commit()
            alarm.cancel(alarmIntent(context, ""))
            if (!fromWorker) work.cancelUniqueWork(WORK)
            return
        }
        val now = ZonedDateTime.now()
        val total = drinks.filter { it.date == now.toLocalDate() }.sumOf { it.amount }
        val previousDue = prefs.getLong(DUE, 0)
        val keep = !reset && previousDue > 0 && !prefs.getString(TOKEN, null).isNullOrEmpty()
        val due = if (keep) previousDue else ReminderPolicy.next(now, settings, total >= settings.goal).toInstant().toEpochMilli()
        val token = if (keep) prefs.getString(TOKEN, "")!! else UUID.randomUUID().toString()
        check(prefs.edit().putLong(DUE, due).putString(TOKEN, token).commit())
        val pending = alarmIntent(context, token)
        val trigger = due.coerceAtLeast(System.currentTimeMillis() + 1000)
        if (settings.preciseReminders && exactAllowed(context)) {
            try {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            } catch (_: SecurityException) {
                // Permission can be revoked between the check and the system call.
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            }
        } else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)

        val backup = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInputData(workDataOf(TOKEN to token))
            .setInitialDelay((due + BACKUP_DELAY - System.currentTimeMillis()).coerceAtLeast(1000), TimeUnit.MILLISECONDS)
            .build()
        val policy = when {
            fromWorker -> ExistingWorkPolicy.APPEND_OR_REPLACE
            keep -> ExistingWorkPolicy.KEEP
            else -> ExistingWorkPolicy.REPLACE
        }
        work.enqueueUniqueWork(WORK, policy, backup)
    }

    @Synchronized
    fun deliver(context: Context, token: String, fromWorker: Boolean = false) {
        val prefs = preferences(context)
        val due = prefs.getLong(DUE, 0)
        val now = ZonedDateTime.now()
        if (!ReminderPolicy.shouldDeliver(token, prefs.getString(TOKEN, null), due, now.toInstant().toEpochMilli())) return
        DrinkRepository(context).use { repo ->
            val settings = repo.settings()
            val drinks = repo.all()
            val total = drinks.filter { it.date == now.toLocalDate() }.sumOf { it.amount }
            // Claim a scheduled occurrence before either the alarm or its backup posts a notification.
            check(prefs.edit().clear().commit())
            try {
                try {
                    if (ReminderPolicy.mayNotify(now, settings, total >= settings.goal)) notify(context, (settings.goal - total).coerceAtLeast(0), settings)
                } finally { schedule(context, settings, drinks, fromWorker = fromWorker) }
            } catch (error: Exception) {
                prefs.edit().putLong(DUE, due).putString(TOKEN, token).commit()
                throw error
            }
        }
    }

    fun restore(context: Context, reset: Boolean = false, fromWorker: Boolean = false) {
        DrinkRepository(context).use { schedule(context, it.settings(), it.all(), reset = reset, fromWorker = fromWorker) }
    }

    fun notify(context: Context, remaining: Int, settings: DrinkSettings = currentSettings(context), preview: Boolean = false): Boolean {
        createChannel(context, settings.reminderStyle)
        if (!permission(context, settings.reminderStyle)) return false
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, settings.reminderStyle.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (preview) "饮水提醒测试" else "喝口水，休息一下")
            .setContentText(when {
                preview -> "当前方式：${settings.reminderStyle.label}"
                remaining > 0 -> "今天距离目标还有 $remaining mL，补充一点水分吧。"
                else -> "保持补水节奏，喝水后记得记录。"
            })
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open).setAutoCancel(true).build()
        return try {
            NotificationManagerCompat.from(context).notify(if (preview) 1003 else 1001, notification)
            true
        } catch (_: SecurityException) { false }
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val token = inputData.getString("token")
        try {
            if (token == null) Reminders.restore(applicationContext, fromWorker = true)
            else Reminders.deliver(applicationContext, token, fromWorker = true)
        } catch (_: Exception) { return Result.retry() }
        return Result.success()
    }
}

private val receiverExecutor = Executors.newSingleThreadExecutor()

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra("token") ?: return
        val result = goAsync()
        receiverExecutor.execute {
            try { Reminders.deliver(context.applicationContext, token) }
            catch (error: Exception) { Log.e("DrinkReminder", "Alarm delivery failed; backup work will retry", error) }
            finally { result.finish() }
        }
    }
}

class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reset = intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED
        val result = goAsync()
        receiverExecutor.execute {
            try { Reminders.restore(context.applicationContext, reset) }
            catch (error: Exception) { Log.e("DrinkReminder", "Unable to restore reminder", error) }
            finally { result.finish() }
        }
    }
}
