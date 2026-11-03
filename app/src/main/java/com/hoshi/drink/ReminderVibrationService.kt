package com.hoshi.drink

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/** Runs only while a user-requested reminder is vibrating; never a scheduling keepalive. */
class ReminderVibrationService : Service() {
    private var currentSession: String? = null
    private var preview = false
    private var traceId: String? = null
    private var lastHeartbeat = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val checkControls = object : Runnable {
        override fun run() {
            val id = currentSession ?: return
            try {
                val settings = DrinkRepository(this@ReminderVibrationService).use { it.settings() }
                val blocked = ReminderVibration.blockedReason(this@ReminderVibrationService, settings)
                if (!ReminderVibration.isCurrent(id) || !settings.repeatVibration ||
                    settings.reminderStyle != ReminderStyle.VIBRATE || blocked != null) {
                    ReminderDiagnostics.record(this@ReminderVibrationService, "持续服务检查停止条件", fields = mapOf("sessionId" to id, "traceId" to traceId,
                        "repeatEnabled" to settings.repeatVibration, "style" to settings.reminderStyle.name, "blockedReason" to blocked))
                    ReminderVibration.endSession(this@ReminderVibrationService, id, "通知、勿扰或震动设置已变化")
                    stopSelf()
                    return
                }
            } catch (error: RuntimeException) {
                ReminderDiagnostics.record(this@ReminderVibrationService, "检查震动设置失败", error = error, fields = mapOf("sessionId" to id, "traceId" to traceId))
                ReminderVibration.endSession(this@ReminderVibrationService, id, "检查失败")
                stopSelf()
                return
            }
            if (SystemClock.elapsedRealtime() - lastHeartbeat >= 30_000) {
                lastHeartbeat = SystemClock.elapsedRealtime()
                ReminderDiagnostics.record(this@ReminderVibrationService, "持续服务心跳", fields = mapOf("sessionId" to id, "traceId" to traceId))
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ReminderDiagnostics.record(this, "持续服务创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("session")
        val requestedPreview = intent?.getBooleanExtra("preview", false) ?: false
        val requestedTrace = intent?.getStringExtra("traceId")
        ReminderDiagnostics.record(this, "持续服务收到启动请求", fields = mapOf("sessionId" to id, "traceId" to requestedTrace,
            "startId" to startId, "flags" to flags, "preview" to requestedPreview))
        if (id == null || !ReminderVibration.isCurrent(id)) {
            ReminderDiagnostics.record(this, "持续服务忽略失效启动", fields = mapOf("sessionId" to id, "traceId" to requestedTrace))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        currentSession = id
        preview = requestedPreview
        traceId = requestedTrace
        lastHeartbeat = SystemClock.elapsedRealtime()
        try {
            val settings = DrinkRepository(this).use { it.settings() }
            val notification = Reminders.notification(this, 0, settings, preview, repeating = true, session = id)
            if (Build.VERSION.SDK_INT >= 34) startForeground(ReminderVibration.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ReminderVibration.NOTIFICATION_ID, notification)
            ReminderDiagnostics.record(this, "前台服务通知请求完成", fields = mapOf("sessionId" to id, "traceId" to traceId, "type" to "specialUse"))
            ReminderDiagnostics.snapshot(this, "持续服务启动", traceId)
            if (!ReminderVibration.beginSession(this, id, settings, preview)) {
                ReminderVibration.endSession(this, id, "服务启动前已确认或权限失效")
                stopSelf(startId)
                return START_NOT_STICKY
            }
            handler.removeCallbacks(checkControls)
            handler.postDelayed(checkControls, 1000)
        } catch (error: RuntimeException) {
            ReminderDiagnostics.record(this, "持续震动服务失败", error = error, fields = mapOf("sessionId" to id, "traceId" to traceId))
            val stillCurrent = ReminderVibration.isCurrent(id)
            ReminderVibration.endSession(this, id, "服务失败")
            if (stillCurrent) {
                try {
                    val settings = DrinkRepository(this).use { it.settings() }
                    ReminderDiagnostics.record(this, "持续服务降级为单次提醒", fields = mapOf("sessionId" to id, "traceId" to traceId))
                    Reminders.postSingle(this, 0, settings, preview, traceId)
                } catch (fallbackError: RuntimeException) {
                    ReminderDiagnostics.record(this, "服务降级失败", error = fallbackError, fields = mapOf("sessionId" to id, "traceId" to traceId))
                }
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ReminderDiagnostics.record(this, "持续服务销毁", fields = mapOf("sessionId" to currentSession, "traceId" to traceId))
        handler.removeCallbacks(checkControls)
        currentSession?.let { ReminderVibration.endSession(this, it, "服务销毁") }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        ReminderDiagnostics.record(this, "持续服务任务被移除", fields = mapOf("sessionId" to currentSession, "traceId" to traceId))
        currentSession?.let { ReminderVibration.endSession(this, it, "用户移除应用任务") }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
