package com.hoshi.drink

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.net.Uri
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
    // Invalidate rolling countdowns saved by earlier app versions.
    internal const val POLICY_VERSION = 2
    const val ACTION_OPEN_AND_STOP = "com.hoshi.drink.OPEN_AND_STOP"

    fun stopVibration(context: Context) = ReminderVibration.stop(context, "用户确认提醒")

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
            ReminderVibration.stop(context, "提醒已关闭")
            prefs.edit().clear().commit()
            alarm.cancel(alarmIntent(context, ""))
            if (!fromWorker) work.cancelUniqueWork(WORK)
            ReminderDiagnostics.record(context, "取消定时提醒")
            return
        }
        val now = ZonedDateTime.now()
        val total = drinks.filter { it.date == now.toLocalDate() }.sumOf { it.amount }
        val previousDue = prefs.getLong(DUE, 0)
        val keep = !reset && prefs.getInt("policyVersion", 0) == POLICY_VERSION && previousDue > 0 && !prefs.getString(TOKEN, null).isNullOrEmpty()
        val due = if (keep) previousDue else ReminderPolicy.next(now, settings, total >= settings.goal).toInstant().toEpochMilli()
        val token = if (keep) prefs.getString(TOKEN, "")!! else UUID.randomUUID().toString()
        val pending = alarmIntent(context, token)
        val trigger = due.coerceAtLeast(System.currentTimeMillis() + 1000)
        var mode = "普通闹钟（可能延迟）"
        if (settings.preciseReminders && exactAllowed(context)) {
            try {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
                mode = "精确闹钟"
            } catch (_: SecurityException) {
                // Permission can be revoked between the check and the system call.
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            }
        } else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        check(prefs.edit().putLong(DUE, due).putString(TOKEN, token).putInt("policyVersion", POLICY_VERSION)
            .putString("mode", mode).commit())
        ReminderDiagnostics.record(context, "安排提醒", "计划=${ReminderDiagnostics.time(due)} 模式=$mode 精确权限=${exactAllowed(context)} 保留原任务=$keep 标识=${token.take(8)}")

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
        if (prefs.getInt("policyVersion", 0) != POLICY_VERSION) {
            ReminderDiagnostics.record(context, "重排旧版本任务")
            restore(context, reset = true, fromWorker = fromWorker)
            return
        }
        val due = prefs.getLong(DUE, 0)
        val now = ZonedDateTime.now()
        val nowMillis = now.toInstant().toEpochMilli()
        ReminderDiagnostics.record(context, "收到提醒", "来源=${if (fromWorker) "后台兜底" else "系统闹钟"} 标识=${token.take(8)} 计划=${ReminderDiagnostics.time(due)} 延迟=${nowMillis - due}ms")
        if (!ReminderPolicy.shouldDeliver(token, prefs.getString(TOKEN, null), due, nowMillis)) {
            ReminderDiagnostics.record(context, "忽略提醒", "过期标识、重复或尚未到点")
            if (token == prefs.getString(TOKEN, null) && due > nowMillis) restore(context)
            return
        }
        DrinkRepository(context).use { repo ->
            val settings = repo.settings()
            val drinks = repo.all()
            val total = drinks.filter { it.date == now.toLocalDate() }.sumOf { it.amount }
            // Persist the next occurrence before notification/vibration code can fail.
            schedule(context, settings, drinks, fromWorker = fromWorker)
            if (ReminderPolicy.mayNotify(now, settings, total >= settings.goal)) {
                notify(context, (settings.goal - total).coerceAtLeast(0), settings)
            } else {
                ReminderDiagnostics.record(context, "不发送通知", "已关闭、超出时段或达标暂停")
            }
        }
    }

    fun restore(context: Context, reset: Boolean = false, fromWorker: Boolean = false) {
        DrinkRepository(context).use { schedule(context, it.settings(), it.all(), reset = reset, fromWorker = fromWorker) }
    }

    fun notification(context: Context, remaining: Int, settings: DrinkSettings, preview: Boolean,
                     repeating: Boolean = false, session: String? = null, manual: Boolean = repeating): Notification {
        val openIntent = Intent(context, MainActivity::class.java)
        if (repeating) openIntent.setAction(ACTION_OPEN_AND_STOP).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, if (repeating) 1004 else 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, settings.reminderStyle.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (preview) "饮水提醒测试" else "喝口水，休息一下")
            .setContentText(when {
                repeating -> "规律震动中，点此进入应用或选择停止震动。"
                preview -> "当前方式：${settings.reminderStyle.label}"
                remaining > 0 -> "今天距离目标还有 $remaining mL，补充一点水分吧。"
                else -> "保持补水节奏，喝水后记得记录。"
            })
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setSilent(manual)
            .setContentIntent(open).setAutoCancel(!repeating)
        if (repeating) {
            val stopIntent = Intent(context, StopVibrationReceiver::class.java).putExtra("session", session)
                .setData(Uri.parse("hoshidrink://vibration/stop/${session.orEmpty()}"))
            val stop = PendingIntent.getBroadcast(context, 1004, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setOngoing(true).setDeleteIntent(stop)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(R.drawable.ic_notification, "停止震动", stop)
        }
        return builder.build()
    }

    @Synchronized
    fun notify(context: Context, remaining: Int, settings: DrinkSettings = currentSettings(context), preview: Boolean = false): Boolean {
        createChannel(context, settings.reminderStyle)
        if (!permission(context, settings.reminderStyle)) {
            ReminderDiagnostics.record(context, "通知被关闭", settings.reminderStyle.channelId)
            return false
        }
        stopVibration(context)
        if (ReminderVibration.startRepeating(context, settings, preview)) return true
        return postSingle(context, remaining, settings, preview)
    }

    fun postSingle(context: Context, remaining: Int, settings: DrinkSettings, preview: Boolean): Boolean {
        if (!permission(context, settings.reminderStyle)) return false
        val manual = settings.reminderStyle.vibrate && ReminderVibration.allowed(context, settings) &&
            (ReminderVibration.silent(context) || (settings.repeatVibration && settings.reminderStyle == ReminderStyle.VIBRATE))
        return try {
            NotificationManagerCompat.from(context).notify(if (preview) 1003 else 1001,
                notification(context, remaining, settings, preview, manual = manual))
            ReminderDiagnostics.record(context, "通知请求已提交", "试发=$preview 渠道=${settings.reminderStyle.channelId}")
            if (manual) ReminderVibration.pulse(context, settings, preview)
            true
        } catch (error: RuntimeException) {
            ReminderDiagnostics.record(context, "发送通知失败", error.javaClass.simpleName)
            false
        }
    }
}

class StopVibrationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getStringExtra("session")
        if (session == null) Reminders.stopVibration(context)
        else ReminderVibration.endSession(context, session, "通知停止按钮或划掉通知")
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val token = inputData.getString("token")
        try {
            if (token == null) Reminders.restore(applicationContext, fromWorker = true)
            else Reminders.deliver(applicationContext, token, fromWorker = true)
        } catch (error: Exception) {
            ReminderDiagnostics.record(applicationContext, "兜底任务失败", error.javaClass.simpleName)
            return Result.retry()
        }
        return Result.success()
    }
}

private val receiverExecutor = Executors.newSingleThreadExecutor()

private fun BroadcastReceiver.dispatch(context: Context, action: String, block: () -> Unit) {
    // goAsync() keeps the broadcast alive, but does not hold the CPU awake after onReceive returns.
    val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HoshiDrink:ReminderReceiver")
    wakeLock.acquire(10_000L)
    val result = goAsync()
    receiverExecutor.execute {
        try { block() }
        catch (error: Exception) { ReminderDiagnostics.record(context, "$action 失败", error.javaClass.simpleName) }
        finally {
            if (wakeLock.isHeld) wakeLock.release()
            result.finish()
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra("token") ?: return
        dispatch(context, "闹钟广播") { Reminders.deliver(context.applicationContext, token) }
    }
}

class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reset = intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED
        dispatch(context, "恢复广播") {
            ReminderDiagnostics.record(context, "恢复提醒", intent.action.orEmpty())
            Reminders.restore(context.applicationContext, reset)
        }
    }
}
