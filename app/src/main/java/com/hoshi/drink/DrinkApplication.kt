package com.hoshi.drink

import android.app.Application
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.PowerManager
import androidx.core.content.ContextCompat

class DrinkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ReminderDiagnostics.record(this, "进程启动")
        ReminderDiagnostics.snapshot(this, "进程启动")
        ReminderDiagnostics.previousExits(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous != null) Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { ReminderDiagnostics.crash(this, thread, error) }
            finally { previous.uncaughtException(thread, error) }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        try {
            ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    ReminderDiagnostics.record(context, "系统状态变化", intent.action.orEmpty())
                    ReminderDiagnostics.snapshot(context, intent.action.orEmpty())
                }
            }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (error: RuntimeException) {
            ReminderDiagnostics.record(this, "系统状态监听注册失败", error = error)
        }
    }
}
