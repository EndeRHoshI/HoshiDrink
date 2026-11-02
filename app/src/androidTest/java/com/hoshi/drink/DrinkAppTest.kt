package com.hoshi.drink

import android.app.NotificationManager
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DrinkAppTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun chooseVibrationAndPreviewWithoutEnablingReminders() {
        val command = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant com.hoshi.drink android.permission.POST_NOTIFICATIONS")
        ParcelFileDescriptor.AutoCloseInputStream(command).use { it.readBytes() }
        DrinkRepository(context).use { repo ->
            val original = repo.settings()
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            try {
                compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
                compose.onNodeWithContentDescription("选择提醒方式").performScrollTo().performClick()
                compose.onAllNodesWithText("仅震动").onLast().performClick()
                compose.waitUntil(5000) { repo.settings().reminderStyle == ReminderStyle.VIBRATE }
                screenshot("reminder-style")
                compose.onNodeWithText("试发提醒").performScrollTo().performClick()
                compose.waitUntil(5000) { notificationManager.activeNotifications.any { it.id == 1003 } }
                assertEquals(original.enabled, repo.settings().enabled)
                assertEquals(ReminderStyle.VIBRATE.channelId, notificationManager.activeNotifications.single { it.id == 1003 }.notification.channelId)
                compose.onNodeWithText("应用与后台设置").performScrollTo()
                compose.waitUntil(7000) { compose.onAllNodesWithText("测试通知已发送").fetchSemanticsNodes().isEmpty() }
                screenshot("reminder-reliability")
            } finally {
                notificationManager.cancel(1003)
                repo.save(original)
                Reminders.restore(context, reset = true)
            }
        }
    }

    @Test fun recordCustomAmountChangeGoalBrowseHistoryAndUndo() {
        DrinkRepository(context).use { repo ->
            val before = repo.all().size
            compose.onNodeWithText("记录 250 mL").performScrollTo().performClick()
            compose.waitUntil(5000) { repo.all().size == before + 1 }
            compose.onNodeWithText("自定义容量").performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("420")
            compose.onNodeWithText("确定").performClick()
            compose.onNodeWithText("记录 420 mL").performScrollTo().performClick()
            compose.waitUntil(5000) { repo.all().size == before + 2 }

            compose.onNodeWithText("HoshiDrink").performScrollTo()
            screenshot("today")
            compose.onNodeWithText("设置", useUnmergedTree = true).performClick()
            compose.onNodeWithText("饮水目标").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("0")
            compose.onNodeWithText("确定").assertIsNotEnabled()
            compose.onNode(hasSetTextAction()).performTextReplacement("2200")
            compose.onNodeWithText("确定").performClick()
            compose.waitUntil(5000) { repo.settings().goal == 2200 }
            compose.onNodeWithText("90 分钟").performScrollTo().performClick()
            compose.waitUntil(5000) { repo.settings().interval == 90 }
            compose.onNodeWithText("按自己的节奏").performScrollTo()
            screenshot("settings")

            compose.onNodeWithText("统计", useUnmergedTree = true).performClick()
            compose.onNodeWithText("最近 7 天").assertExists()
            screenshot("statistics")
            compose.onNodeWithContentDescription("前七天").performClick()
            compose.onNodeWithText("往期记录").assertExists()
            compose.onNodeWithContentDescription("后七天").performClick()
            compose.onAllNodesWithContentDescription("删除 420 mL 记录").onFirst().performScrollTo().performClick()
            compose.waitUntil(5000) { repo.all().size == before + 1 }
            compose.onNodeWithText("撤销").performClick()
            compose.waitUntil(5000) { repo.all().size == before + 2 }
            assertEquals(420, repo.all().first().amount)
        }
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

@RunWith(AndroidJUnit4::class)
class DrinkStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun recordsPersistAndCanBeDeletedAndRestored() {
        val drink = DrinkRepository(context).use { it.add(375, 1000) }
        try {
            DrinkRepository(context).use { repo ->
                assertTrue(repo.all().contains(drink))
                repo.delete(drink.id)
                assertFalse(repo.all().contains(drink))
                repo.restore(drink)
                assertTrue(repo.all().contains(drink))
                assertThrows(IllegalArgumentException::class.java) { repo.add(0) }
                assertThrows(IllegalArgumentException::class.java) { repo.add(2001) }
            }
        } finally { DrinkRepository(context).use { it.delete(drink.id) } }
    }

    @Test fun reminderWorkerPostsSystemNotificationAndHonorsDisabledSetting() = runBlocking {
        val command = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant com.hoshi.drink android.permission.POST_NOTIFICATIONS")
        ParcelFileDescriptor.AutoCloseInputStream(command).use { it.readBytes() }
        DrinkRepository(context).use { repo ->
            val original = repo.settings()
            val manager = context.getSystemService(NotificationManager::class.java)
            try {
                repo.save(original.copy(enabled = true, start = 0, end = 1439, stopAtGoal = false))
                Reminders.createChannel(context)
                assertTrue(Reminders.permission(context))
                val pending = context.getSharedPreferences("reminder_schedule", 0)
                pending.edit().putLong("due", System.currentTimeMillis() - 1000).putString("token", "worker-test").putInt("policyVersion", Reminders.POLICY_VERSION).commit()
                TestListenableWorkerBuilder<ReminderWorker>(context).setInputData(workDataOf("token" to "worker-test")).build().doWork()
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (manager.activeNotifications.none { it.id == 1001 } && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
                assertTrue(manager.activeNotifications.any { it.id == 1001 })
                manager.cancel(1001)
                while (manager.activeNotifications.any { it.id == 1001 } && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
                repo.save(original.copy(enabled = false))
                pending.edit().putLong("due", System.currentTimeMillis() - 1000).putString("token", "disabled-test").putInt("policyVersion", Reminders.POLICY_VERSION).commit()
                TestListenableWorkerBuilder<ReminderWorker>(context).setInputData(workDataOf("token" to "disabled-test")).build().doWork()
                assertFalse(manager.activeNotifications.any { it.id == 1001 })
            } finally {
                repo.save(original)
                manager.cancel(1001)
                Reminders.schedule(context, original, repo.all())
            }
        }
    }
}
