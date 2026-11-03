package com.hoshi.drink

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ReminderDiagnostics {
    private val format = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

    fun time(timestamp: Long): String = if (timestamp <= 0) "未安排" else
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(format)

    @Synchronized
    fun record(context: Context, event: String, details: String = "") {
        val line = "${time(System.currentTimeMillis())} $event $details".trim()
        Log.i("DrinkReminder", line)
        val prefs = context.applicationContext.getSharedPreferences("reminder_diagnostics", Context.MODE_PRIVATE)
        val lines = prefs.getString("events", "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
        prefs.edit().putString("events", (lines + line).takeLast(150).joinToString("\n")).apply()
    }

    fun summary(context: Context): String {
        val prefs = context.getSharedPreferences("reminder_schedule", Context.MODE_PRIVATE)
        return "下次计划：${time(prefs.getLong("due", 0))}\n实际调度：${prefs.getString("mode", "未安排")}"
    }

    fun report(context: Context): String {
        val settings = DrinkRepository(context).use { it.settings() }
        val notifications = context.getSystemService(NotificationManager::class.java)
        val channel = notifications.getNotificationChannel(settings.reminderStyle.channelId)
        val events = context.getSharedPreferences("reminder_diagnostics", Context.MODE_PRIVATE).getString("events", "").orEmpty()
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        return """
            HoshiDrink $version 提醒诊断
            时间：${time(System.currentTimeMillis())} ${ZoneId.systemDefault()}
            设备：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}
            提醒开启：${settings.enabled}；准时提醒：${settings.preciseReminders}；精确权限：${Reminders.exactAllowed(context)}
            开始/结束/间隔（分钟）：${settings.start}/${settings.end}/${settings.interval}
            达标暂停：${settings.stopAtGoal}；方式：${settings.reminderStyle.label}；持续震动：${settings.repeatVibration}
            通知允许：${Reminders.permission(context, settings.reminderStyle)}；渠道级别：${channel?.importance}；渠道震动：${channel?.shouldVibrate()}
            勿扰过滤器：${notifications.currentInterruptionFilter}；响铃模式：${context.getSystemService(AudioManager::class.java).ringerMode}
            电池不受限：${context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)}
            系统限制后台：${if (Build.VERSION.SDK_INT >= 28) context.getSystemService(ActivityManager::class.java).isBackgroundRestricted else false}
            ${summary(context)}
            记录的是 API 请求和生命周期事件，不代表系统已实际展示通知或驱动马达。
        """.trimIndent() + "\n\n最近事件（最多 150 条）：\n$events"
    }
}
