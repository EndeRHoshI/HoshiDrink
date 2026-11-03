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
        resumed = value
        if (value) stop(context, "应用进入前台")
    }

    @Synchronized
    fun allowed(context: Context, settings: DrinkSettings): Boolean {
        if (!settings.reminderStyle.vibrate || !Reminders.permission(context, settings.reminderStyle)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(settings.reminderStyle.channelId) ?: return false
        return manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL &&
            channel.shouldVibrate() && channel.importance >= NotificationManager.IMPORTANCE_DEFAULT &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED &&
            vibrator(context).hasVibrator()
    }

    fun silent(context: Context) = context.getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_SILENT

    @Synchronized
    fun startRepeating(context: Context, settings: DrinkSettings, preview: Boolean): Boolean {
        if (!settings.repeatVibration || settings.reminderStyle != ReminderStyle.VIBRATE || !allowed(context, settings)) return false
        if (resumed && !preview) {
            ReminderDiagnostics.record(context, "震动跳过", "用户正在应用内")
            return false
        }
        stop(context, "替换上一条提醒")
        val id = UUID.randomUUID().toString()
        session = id
        return try {
            ContextCompat.startForegroundService(context.applicationContext,
                Intent(context.applicationContext, ReminderVibrationService::class.java)
                    .putExtra("session", id).putExtra("preview", preview))
            ReminderDiagnostics.record(context, "持续震动服务请求", id.take(8))
            true
        } catch (error: RuntimeException) {
            session = null
            ReminderDiagnostics.record(context, "持续震动服务被拒绝", error.javaClass.simpleName)
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
        if (session != id || (resumed && !preview) || !allowed(context, settings)) return false
        val target = vibrator(context)
        val effect = if (target.hasAmplitudeControl()) VibrationEffect.createWaveform(
            longArrayOf(0, 100, 180, 120, 1400), intArrayOf(0, 50, 110, 50, 0), 0)
        else VibrationEffect.createWaveform(longArrayOf(0, 400, 1400), 0)
        vibrate(context, effect)
        ReminderDiagnostics.record(context, "持续震动请求已提交", id.take(8))
        return true
    }

    @Synchronized
    fun pulse(context: Context, settings: DrinkSettings, preview: Boolean): Boolean {
        if ((resumed && !preview) || !allowed(context, settings)) return false
        return try {
            vibrate(context, VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1))
            ReminderDiagnostics.record(context, "短震动请求已提交")
            true
        } catch (error: RuntimeException) {
            ReminderDiagnostics.record(context, "短震动失败", error.javaClass.simpleName)
            false
        }
    }

    @Synchronized
    fun stop(context: Context, reason: String) {
        val active = session
        session = null // Invalidate queued service starts before canceling the motor.
        try {
            // Never gate cancellation on an asynchronously persisted boolean.
            vibrator(context).cancel()
            ReminderDiagnostics.record(context, "停止震动请求已提交", "$reason 会话=${active?.take(8) ?: "无活动会话"}")
        } catch (error: RuntimeException) {
            ReminderDiagnostics.record(context, "停止震动失败", "$reason ${error.javaClass.simpleName}")
        }
        context.applicationContext.stopService(Intent(context.applicationContext, ReminderVibrationService::class.java))
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    @Synchronized
    fun endSession(context: Context, id: String, reason: String) {
        if (session != id) return
        stop(context, reason)
    }
}
