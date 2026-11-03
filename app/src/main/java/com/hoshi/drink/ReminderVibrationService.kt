package com.hoshi.drink

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Runs only while a user-requested reminder is vibrating; never a scheduling keepalive. */
class ReminderVibrationService : Service() {
    private var currentSession: String? = null
    private var preview = false
    private val handler = Handler(Looper.getMainLooper())
    private val checkControls = object : Runnable {
        override fun run() {
            val id = currentSession ?: return
            try {
                val settings = DrinkRepository(this@ReminderVibrationService).use { it.settings() }
                if (!ReminderVibration.isCurrent(id) || !settings.repeatVibration ||
                    settings.reminderStyle != ReminderStyle.VIBRATE || !ReminderVibration.allowed(this@ReminderVibrationService, settings)) {
                    ReminderVibration.endSession(this@ReminderVibrationService, id, "通知、勿扰或震动设置已变化")
                    stopSelf()
                    return
                }
            } catch (error: RuntimeException) {
                ReminderDiagnostics.record(this@ReminderVibrationService, "检查震动设置失败", error.javaClass.simpleName)
                ReminderVibration.endSession(this@ReminderVibrationService, id, "检查失败")
                stopSelf()
                return
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("session")
        preview = intent?.getBooleanExtra("preview", false) ?: false
        if (id == null || !ReminderVibration.isCurrent(id)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        currentSession = id
        try {
            val settings = DrinkRepository(this).use { it.settings() }
            val notification = Reminders.notification(this, 0, settings, preview, repeating = true, session = id)
            if (Build.VERSION.SDK_INT >= 34) startForeground(ReminderVibration.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ReminderVibration.NOTIFICATION_ID, notification)
            if (!ReminderVibration.beginSession(this, id, settings, preview)) {
                ReminderVibration.endSession(this, id, "服务启动前已确认或权限失效")
                stopSelf(startId)
                return START_NOT_STICKY
            }
            handler.removeCallbacks(checkControls)
            handler.postDelayed(checkControls, 1000)
        } catch (error: RuntimeException) {
            ReminderDiagnostics.record(this, "持续震动服务失败", error.javaClass.simpleName)
            val stillCurrent = ReminderVibration.isCurrent(id)
            ReminderVibration.endSession(this, id, "服务失败")
            if (stillCurrent) {
                try {
                    val settings = DrinkRepository(this).use { it.settings() }
                    Reminders.postSingle(this, 0, settings, preview)
                } catch (fallbackError: RuntimeException) {
                    ReminderDiagnostics.record(this, "服务降级失败", fallbackError.javaClass.simpleName)
                }
            }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(checkControls)
        currentSession?.let { ReminderVibration.endSession(this, it, "服务销毁") }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        currentSession?.let { ReminderVibration.endSession(this, it, "用户移除应用任务") }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
