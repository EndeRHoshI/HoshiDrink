package com.hoshi.drink

import java.time.ZonedDateTime
import java.time.LocalTime

object ReminderPolicy {
    fun shouldDeliver(token: String, scheduledToken: String?, due: Long, now: Long): Boolean =
        token.isNotEmpty() && token == scheduledToken && due > 0 && now >= due

    fun dailyTimes(settings: DrinkSettings): List<LocalTime> =
        (settings.start until settings.end step settings.interval).map { LocalTime.of(it / 60, it % 60) }

    fun next(now: ZonedDateTime, settings: DrinkSettings, reachedGoal: Boolean): ZonedDateTime {
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1).atTime(settings.start / 60, settings.start % 60).atZone(now.zone)
        if (reachedGoal && settings.stopAtGoal) return tomorrow
        for (time in dailyTimes(settings)) {
            val candidate = today.atTime(time).atZone(now.zone)
            if (candidate > now) return candidate
        }
        return tomorrow
    }

    fun mayNotify(now: ZonedDateTime, settings: DrinkSettings, reachedGoal: Boolean): Boolean {
        val minutes = now.hour * 60 + now.minute
        return settings.enabled && minutes >= settings.start && minutes < settings.end && !(settings.stopAtGoal && reachedGoal)
    }
}
