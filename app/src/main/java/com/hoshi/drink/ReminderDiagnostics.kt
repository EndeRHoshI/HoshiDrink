package com.hoshi.drink

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ReminderDiagnostics {
    private val format = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    private const val MAX_BYTES = 1024 * 1024L
    private const val FILE_COUNT = 7
    private val processSession = UUID.randomUUID().toString()
    private val dropped = AtomicInteger()
    private val fileLock = Any()
    private var sequence = 0L
    private val writer = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(2048), { task -> Thread(task, "drink-diagnostics").apply { isDaemon = true } },
        { _, _ -> dropped.incrementAndGet() })

    private fun directory(context: Context) = File(context.filesDir, "diagnostics").apply { mkdirs() }

    fun time(timestamp: Long): String = if (timestamp <= 0) "未安排" else
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(format)

    fun record(context: Context, event: String, details: String = "", error: Throwable? = null,
               fields: Map<String, Any?> = emptyMap(), level: String = if (error == null) "INFO" else "ERROR") {
        try {
            val app = context.applicationContext
            val entry = entry(event, details, error, fields, level)
            val message = "$event $details processSession=$processSession fields=${entry.getJSONObject("fields")}".take(3500)
            if (error == null) Log.i("DrinkReminder", message) else Log.e("DrinkReminder", message, error)
            writer.execute { append(app, entry) }
        } catch (failure: Exception) {
            dropped.incrementAndGet()
            Log.e("DrinkReminder", "Unable to record event: $event", failure)
        }
    }

    private fun entry(event: String, details: String, error: Throwable?, fields: Map<String, Any?>, level: String): JSONObject =
        JSONObject().apply {
            put("schema", 1)
            put("wallTime", Instant.now().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("elapsedMs", SystemClock.elapsedRealtime())
            put("processSession", processSession)
            put("pid", Process.myPid())
            put("tid", Process.myTid())
            put("thread", Thread.currentThread().name)
            put("level", level)
            put("event", event)
            put("details", details.take(8192))
            put("fields", JSONObject(fields))
            if (error != null) {
                put("exception", error.javaClass.name)
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                put("stackTrace", trace.take(24_000))
            }
        }

    private fun append(context: Context, entry: JSONObject) {
        try {
            synchronized(fileLock) {
                entry.put("writeSequence", ++sequence)
                entry.put("droppedSinceLastWrite", dropped.getAndSet(0))
                val root = directory(context)
                val active = File(root, "events-0.jsonl")
                val bytes = (entry.toString() + "\n").toByteArray(Charsets.UTF_8)
                if (active.length() + bytes.size > MAX_BYTES) {
                    File(root, "events-${FILE_COUNT - 1}.jsonl").delete()
                    for (index in FILE_COUNT - 2 downTo 0) {
                        val source = File(root, "events-$index.jsonl")
                        if (source.exists()) check(source.renameTo(File(root, "events-${index + 1}.jsonl")))
                    }
                }
                active.appendBytes(bytes)
            }
        } catch (failure: Exception) {
            // Logging must never prevent an alarm, notification, stop action or lifecycle callback.
            dropped.incrementAndGet()
            Log.e("DrinkReminder", "File logging failed", failure)
        }
    }

    fun crash(context: Context, thread: Thread, error: Throwable) {
        // Do not depend on an async queue when the process is about to terminate.
        append(context.applicationContext, entry("未捕获异常", thread.name, error, emptyMap(), "FATAL"))
    }

    fun snapshot(context: Context, reason: String, traceId: String? = null) {
        val app = context.applicationContext
        writer.execute {
            try {
                val settings = DrinkRepository(app).use { it.settings() }
                val manager = app.getSystemService(NotificationManager::class.java)
                val channel = manager.getNotificationChannel(settings.reminderStyle.channelId)
                val power = app.getSystemService(PowerManager::class.java)
                val fields = mapOf(
                    "traceId" to traceId, "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
                    "sdk" to Build.VERSION.SDK_INT, "version" to app.packageManager.getPackageInfo(app.packageName, 0).versionName,
                    "enabled" to settings.enabled, "start" to settings.start, "end" to settings.end, "interval" to settings.interval,
                    "stopAtGoal" to settings.stopAtGoal, "style" to settings.reminderStyle.name, "repeat" to settings.repeatVibration,
                    "preciseRequested" to settings.preciseReminders, "exactAllowed" to Reminders.exactAllowed(app),
                    "notificationAllowed" to Reminders.permission(app, settings.reminderStyle), "channelId" to channel?.id,
                    "channelImportance" to channel?.importance, "channelVibrate" to channel?.shouldVibrate(),
                    "channelSound" to (channel?.sound != null), "interruptionFilter" to manager.currentInterruptionFilter,
                    "ringerMode" to app.getSystemService(AudioManager::class.java).ringerMode,
                    "interactive" to power.isInteractive, "idle" to power.isDeviceIdleMode, "powerSave" to power.isPowerSaveMode,
                    "batteryUnrestricted" to power.isIgnoringBatteryOptimizations(app.packageName),
                    "backgroundRestricted" to (Build.VERSION.SDK_INT >= 28 && app.getSystemService(ActivityManager::class.java).isBackgroundRestricted),
                    "activeNotificationIds" to manager.activeNotifications.map { it.id }.joinToString(","),
                    "nextDue" to app.getSharedPreferences("reminder_schedule", 0).getLong("due", 0),
                )
                append(app, entry("系统状态快照", reason, null, fields, "INFO"))
            } catch (error: Exception) { append(app, entry("状态快照失败", reason, error, emptyMap(), "ERROR")) }
        }
    }

    fun previousExits(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        writer.execute {
            try {
                context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 5).forEach { exit ->
                    append(context, entry("历史进程退出", "", null, mapOf(
                        "exitTime" to time(exit.timestamp), "exitMs" to exit.timestamp, "reason" to exit.reason, "status" to exit.status,
                        "importance" to exit.importance, "pid" to exit.pid), "INFO"))
                }
            } catch (error: Exception) { append(context, entry("读取历史退出失败", "", error, emptyMap(), "ERROR")) }
        }
    }

    private fun recent(context: Context, limit: Int): String = synchronized(fileLock) {
        val lines = ArrayDeque<String>()
        for (index in FILE_COUNT - 1 downTo 0) {
            val file = File(directory(context), "events-$index.jsonl")
            if (file.exists()) file.useLines { source -> source.forEach { line ->
                if (lines.size == limit) lines.removeFirst()
                lines.addLast(line)
            } }
        }
        lines.joinToString("\n")
    }

    fun export(context: Context, uri: Uri) {
        // Called off the UI thread; wait briefly for already queued events before creating a consistent archive.
        writer.submit { }.get(5, TimeUnit.SECONDS)
        val header = report(context, includeEvents = false)
        val archive = File.createTempFile("reminder-diagnostics-", ".zip", context.cacheDir)
        try {
            // Capture to local storage first so a slow document provider never holds the logging lock.
            synchronized(fileLock) {
                ZipOutputStream(archive.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("diagnostics.txt"))
                    zip.write(header.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                    for (index in FILE_COUNT - 1 downTo 0) {
                        val file = File(directory(context), "events-$index.jsonl")
                        if (file.exists()) {
                            zip.putNextEntry(ZipEntry(file.name))
                            file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                        }
                    }
                }
            }
            context.contentResolver.openOutputStream(uri)?.use { output ->
                archive.inputStream().use { it.copyTo(output) }
            } ?: error("无法打开导出位置")
        } finally { archive.delete() }
    }

    fun summary(context: Context): String {
        val prefs = context.getSharedPreferences("reminder_schedule", Context.MODE_PRIVATE)
        return "下次计划：${time(prefs.getLong("due", 0))}\n实际调度：${prefs.getString("mode", "未安排")}"
    }

    fun report(context: Context, includeEvents: Boolean = true): String {
        val settings = DrinkRepository(context).use { it.settings() }
        val notifications = context.getSystemService(NotificationManager::class.java)
        val channel = notifications.getNotificationChannel(settings.reminderStyle.channelId)
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
            文件日志：files/diagnostics/events-0.jsonl（最新），最多 $FILE_COUNT 个文件，每个约 1 MiB。
            当前进程会话：$processSession
        """.trimIndent() + if (includeEvents) "\n\n最近 150 条事件：\n${recent(context, 150)}" else ""
    }
}
