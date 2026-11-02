package com.hoshi.drink

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Drink(val id: Long, val amount: Int, val timestamp: Long) {
    val date: LocalDate get() = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
}

data class DrinkSettings(
    val goal: Int = 2000,
    val enabled: Boolean = false,
    val interval: Int = 60,
    val start: Int = 8 * 60,
    val end: Int = 22 * 60,
    val stopAtGoal: Boolean = true,
    val reminderStyle: ReminderStyle = ReminderStyle.SOUND,
    val preciseReminders: Boolean = false,
    val repeatVibration: Boolean = false,
)

enum class ReminderStyle(val label: String, val sound: Boolean, val vibrate: Boolean, val channelId: String) {
    SOUND("仅响铃", true, false, "hydration"),
    SOUND_VIBRATE("响铃和震动", true, true, "hydration_sound_vibrate_v1"),
    VIBRATE("仅震动", false, true, "hydration_vibrate_v1"),
    SILENT("静音", false, false, "hydration_silent_v1"),
}

class DrinkRepository(context: Context) : SQLiteOpenHelper(context.applicationContext, "drink.db", null, 1) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE drinks (id INTEGER PRIMARY KEY AUTOINCREMENT, amount INTEGER NOT NULL CHECK(amount BETWEEN 1 AND 2000), timestamp INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX drink_time ON drinks(timestamp)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun settings() = DrinkSettings(
        goal = prefs.getInt("goal", 2000), enabled = prefs.getBoolean("enabled", false),
        interval = prefs.getInt("interval", 60), start = prefs.getInt("start", 480),
        end = prefs.getInt("end", 1320), stopAtGoal = prefs.getBoolean("stopAtGoal", true),
        reminderStyle = ReminderStyle.entries.firstOrNull { it.name == prefs.getString("reminderStyle", null) } ?: ReminderStyle.SOUND,
        preciseReminders = prefs.getBoolean("preciseReminders", false),
        repeatVibration = prefs.getBoolean("repeatVibration", false),
    )

    fun save(settings: DrinkSettings) {
        require(settings.goal in 500..6000 && settings.interval in setOf(30, 60, 90, 120))
        require(settings.start in 0..1439 && settings.end in 0..1439 && settings.start < settings.end)
        check(prefs.edit().putInt("goal", settings.goal).putBoolean("enabled", settings.enabled)
            .putInt("interval", settings.interval).putInt("start", settings.start)
            .putInt("end", settings.end).putBoolean("stopAtGoal", settings.stopAtGoal)
            .putString("reminderStyle", settings.reminderStyle.name).putBoolean("preciseReminders", settings.preciseReminders)
            .putBoolean("repeatVibration", settings.repeatVibration).commit())
    }

    fun all(): List<Drink> = readableDatabase.query("drinks", null, null, null, null, null, "timestamp DESC, id DESC").use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(Drink(cursor.getLong(0), cursor.getInt(1), cursor.getLong(2)))
        }
    }

    fun add(amount: Int, timestamp: Long = System.currentTimeMillis()): Drink {
        require(amount in 1..2000)
        val values = ContentValues().apply { put("amount", amount); put("timestamp", timestamp) }
        val id = writableDatabase.insertOrThrow("drinks", null, values)
        return Drink(id, amount, timestamp)
    }

    fun delete(id: Long) { writableDatabase.delete("drinks", "id = ?", arrayOf(id.toString())) }
    fun restore(drink: Drink) {
        writableDatabase.insertOrThrow("drinks", null, ContentValues().apply {
            put("id", drink.id); put("amount", drink.amount); put("timestamp", drink.timestamp)
        })
    }
}
