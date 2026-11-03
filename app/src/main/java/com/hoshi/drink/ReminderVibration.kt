package com.hoshi.drink

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import java.util.UUID

/** One process-wide owner and lock for all vibration starts and stops. */
object ReminderVibration {
    private var cachedVibrator: Vibrator? = null
    private var session: String? = null
    private var traceId: String? = null
    private var resumed = false
    const val NOTIFICATION_ID = 1004

    @Suppress("DEPRECATION")
    private fun vibrator(context: Context): Vibrator {
        cachedVibrator?.let { return it }
        val app = context.applicationContext
        return (if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java).defaultVibrator
            else app.getSystemService(Vibrator::class.java)).also { cachedVibrator = it }
    }

    @Synchronized
    fun foreground(context: Context, value: Boolean) {
        ReminderDiagnostics.record(context, "震动控制器前台状态", fields = mapOf("previous" to resumed, "resumed" to value, "sessionId" to session))
        resumed = value
        if (value) stop(context, "应用进入前台")
    }

    @Synchronized
    fun isForeground(): Boolean = resumed

    @Synchronized
    fun allowed(context: Context, settings: DrinkSettings): Boolean {
        return blockedReason(context, settings) == null
    }

    @Synchronized
    fun blockedReason(context: Context, settings: DrinkSettings): String? {
        if (!settings.reminderStyle.vibrate) return "提醒方式不包含震动"
        if (!Reminders.permission(context, settings.reminderStyle)) return "通知权限或渠道关闭"
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(settings.reminderStyle.channelId) ?: return "通知渠道不存在"
        return when {
            manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL -> "勿扰过滤器限制"
            !channel.shouldVibrate() -> "通知渠道振动关闭"
            channel.importance < NotificationManager.IMPORTANCE_DEFAULT -> "通知渠道级别过低"
            ContextCompat.checkSelfPermission(context, Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED -> "无震动权限"
            !vibrator(context).hasVibrator() -> "没有可用震动马达"
            else -> null
        }
    }

    fun silent(context: Context) = context.getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_SILENT

    @Synchronized
    fun startRepeating(context: Context, settings: DrinkSettings, preview: Boolean, trace: String? = null): Boolean {
        if (!settings.repeatVibration || settings.reminderStyle != ReminderStyle.VIBRATE) return false
        blockedReason(context, settings)?.let { reason ->
            ReminderDiagnostics.record(context, "持续震动条件不满足", reason, fields = mapOf("traceId" to trace))
            return false
        }
        stop(context, "替换上一条提醒")
        val id = UUID.randomUUID().toString()
        session = id
        traceId = trace
        return try {
            ContextCompat.startForegroundService(context.applicationContext,
                Intent(context.applicationContext, ReminderVibrationService::class.java)
                    .putExtra("session", id).putExtra("preview", preview).putExtra("traceId", trace))
            ReminderDiagnostics.record(context, "持续震动服务请求", fields = mapOf("sessionId" to id, "traceId" to trace, "preview" to preview, "foreground" to resumed))
            true
        } catch (error: RuntimeException) {
            session = null
            traceId = null
            ReminderDiagnostics.record(context, "持续震动服务被拒绝", error = error, fields = mapOf("sessionId" to id, "traceId" to trace))
            false
        }
    }

    @Synchronized
    fun isCurrent(id: String): Boolean = session == id

    @Suppress("DEPRECATION")
    private fun vibrate(context: Context, effect: VibrationEffect) {
        val target = vibrator(context)
        if (Build.VERSION.SDK_INT >= 33) target.vibrate(effect, VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build())
        else target.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
    }

    @Synchronized
    fun beginSession(context: Context, id: String, settings: DrinkSettings, preview: Boolean): Boolean {
        val blocked = blockedReason(context, settings)
        // A reminder may begin while the app is already visible. Entering the app or pressing
        // stop invalidates the session, which still prevents a queued service from restarting it.
        if (session != id || blocked != null) {
            ReminderDiagnostics.record(context, "震动启动被取消", fields = mapOf("sessionId" to id, "currentSessionId" to session,
                "traceId" to traceId, "foreground" to resumed, "preview" to preview, "blockedReason" to blocked))
            return false
        }
        val target = vibrator(context)
        val effect = if (target.hasAmplitudeControl()) VibrationEffect.createWaveform(
            longArrayOf(0, 100, 180, 120, 1400), intArrayOf(0, 50, 110, 50, 0), 0)
        else VibrationEffect.createWaveform(longArrayOf(0, 400, 1400), 0)
        vibrate(context, effect)
        ReminderDiagnostics.record(context, "持续震动请求已提交", fields = mapOf("sessionId" to id, "traceId" to traceId,
            "amplitudeControl" to target.hasAmplitudeControl(), "repeatIndex" to 0, "onMs" to 400, "pauseMs" to 1400,
            "usage" to "ALARM", "api" to if (Build.VERSION.SDK_INT >= 33) "VibrationAttributes" else "AudioAttributes"))
        return true
    }

    @Synchronized
    fun pulse(context: Context, settings: DrinkSettings, preview: Boolean, trace: String? = null): Boolean {
        val blocked = blockedReason(context, settings)
        if (blocked != null) {
            ReminderDiagnostics.record(context, "短震动跳过", fields = mapOf("traceId" to trace, "foreground" to resumed,
                "preview" to preview, "blockedReason" to blocked))
            return false
        }
        return try {
            vibrate(context, VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1))
            ReminderDiagnostics.record(context, "短震动请求已提交", fields = mapOf("traceId" to trace, "foreground" to resumed,
                "preview" to preview))
            true
        } catch (error: RuntimeException) {
            ReminderDiagnostics.record(context, "短震动失败", error = error, fields = mapOf("traceId" to trace))
            false
        }
    }

    @Synchronized
    fun stop(context: Context, reason: String) {
        val active = session
        val trace = traceId
        session = null // Invalidate queued service starts before canceling the motor.
        traceId = null
        try {
            // Never gate cancellation on an asynchronously persisted boolean.
            vibrator(context).cancel()
            ReminderDiagnostics.record(context, "停止震动请求已提交", reason, fields = mapOf("sessionId" to active, "traceId" to trace))
        } catch (error: RuntimeException) {
            ReminderDiagnostics.record(context, "停止震动失败", reason, error = error, fields = mapOf("sessionId" to active, "traceId" to trace))
        }
        context.applicationContext.stopService(Intent(context.applicationContext, ReminderVibrationService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    @Synchronized
    fun endSession(context: Context, id: String, reason: String) {
        if (session != id) {
            ReminderDiagnostics.record(context, "忽略旧会话停止请求", reason, fields = mapOf("requestedSessionId" to id, "currentSessionId" to session))
            return
        }
        stop(context, reason)
    }
}
