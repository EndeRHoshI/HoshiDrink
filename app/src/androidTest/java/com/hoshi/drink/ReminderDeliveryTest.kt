package com.hoshi.drink

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ReminderDeliveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("reminder_schedule", Context.MODE_PRIVATE)
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Expected system state did not arrive", condition())
    }

    @Test fun notificationModesHaveIndependentSoundAndVibrationChannels() {
        DrinkRepository(context).use { repo ->
            val original = repo.settings()
            try {
                ReminderStyle.entries.forEach { mode ->
                    repo.save(original.copy(reminderStyle = mode))
                    assertEquals(mode, repo.settings().reminderStyle)
                    Reminders.createChannel(context, mode)
                    val channel = manager.getNotificationChannel(mode.channelId)
                    assertEquals(mode.vibrate, channel.shouldVibrate())
                    assertEquals(mode.sound, channel.sound != null)
                    if (mode.vibrate) assertArrayEquals(longArrayOf(0, 250, 120, 250), channel.vibrationPattern)
                }
            } finally { repo.save(original) }
        }
    }

    @Test fun schedulerPreservesDueTimeRestoresAndCancels() {
        DrinkRepository(context).use { repo ->
            val original = repo.settings()
            val settings = original.copy(enabled = true, start = 0, end = 1439, stopAtGoal = false, preciseReminders = true)
            try {
                repo.save(settings)
                Reminders.schedule(context, settings, repo.all())
                val due = prefs.getLong("due", 0)
                val token = prefs.getString("token", null)
                assertTrue(due > System.currentTimeMillis())
                Reminders.restore(context)
                assertEquals(due, prefs.getLong("due", 0))
                assertEquals(token, prefs.getString("token", null))
                val backup = WorkManager.getInstance(context).getWorkInfosForUniqueWork("drink-reminder").get(5, TimeUnit.SECONDS)
                assertEquals(1, backup.count { it.state == WorkInfo.State.ENQUEUED })
                repo.save(settings.copy(enabled = false))
                Reminders.restore(context)
                assertEquals(0, prefs.getLong("due", 0))
                assertNull(prefs.getString("token", null))
            } finally {
                repo.save(original)
                Reminders.restore(context, reset = true)
            }
        }
    }

    @Test fun alarmAndBackupCannotPostTheSameOccurrenceTwice() {
        shell("pm grant com.hoshi.drink android.permission.POST_NOTIFICATIONS")
        DrinkRepository(context).use { repo ->
            val original = repo.settings()
            val settings = original.copy(enabled = true, start = 0, end = 1439, stopAtGoal = false, reminderStyle = ReminderStyle.VIBRATE)
            try {
                repo.save(settings)
                prefs.edit().putLong("due", System.currentTimeMillis() - 1000).putString("token", "dedup-test").commit()
                Reminders.deliver(context, "dedup-test")
                await { manager.activeNotifications.any { it.id == 1001 } }
                val posted = manager.activeNotifications.single { it.id == 1001 }
                assertEquals(ReminderStyle.VIBRATE.channelId, posted.notification.channelId)
                val nextToken = prefs.getString("token", null)
                manager.cancel(1001)
                await { manager.activeNotifications.none { it.id == 1001 } }
                Reminders.deliver(context, "dedup-test", fromWorker = true)
                assertEquals(nextToken, prefs.getString("token", null))
                assertTrue(manager.activeNotifications.none { it.id == 1001 })
            } finally {
                manager.cancel(1001)
                repo.save(original)
                Reminders.restore(context, reset = true)
            }
        }
    }

    @SuppressLint("ScheduleExactAlarm")
    @Test fun systemAlarmDeliversWhileEmulatorIsInDeepIdle() {
        assumeTrue("This test changes idle state only on an emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        shell("pm grant com.hoshi.drink android.permission.POST_NOTIFICATIONS")
        shell("appops set com.hoshi.drink SCHEDULE_EXACT_ALARM allow")
        assertTrue(Reminders.exactAllowed(context))
        DrinkRepository(context).use { repo ->
            val original = repo.settings()
            try {
                repo.save(original.copy(enabled = true, preciseReminders = true, start = 0, end = 1439, stopAtGoal = false, reminderStyle = ReminderStyle.VIBRATE))
                manager.cancel(1001)
                await { manager.activeNotifications.none { it.id == 1001 } }
                shell("dumpsys battery unplug")
                shell("input keyevent KEYCODE_SLEEP")
                assertTrue(shell("dumpsys deviceidle force-idle").contains("deep idle"))
                val due = System.currentTimeMillis() + 2000
                prefs.edit().putLong("due", due).putString("token", "idle-test").commit()
                val alarm = PendingIntent.getBroadcast(context, 1002,
                    Intent(context, ReminderReceiver::class.java).setAction("com.hoshi.drink.REMIND").putExtra("token", "idle-test"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, alarm)
                await { manager.activeNotifications.any { it.id == 1001 } }
                assertNotEquals("idle-test", prefs.getString("token", null))
            } finally {
                shell("dumpsys deviceidle unforce")
                shell("dumpsys battery reset")
                shell("input keyevent KEYCODE_WAKEUP")
                manager.cancel(1001)
                repo.save(original)
                Reminders.restore(context, reset = true)
            }
        }
    }
}
