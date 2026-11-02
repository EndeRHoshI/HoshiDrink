package com.hoshi.drink

import java.time.ZonedDateTime

object ReminderPolicy {
    fun shouldDeliver(token: String, scheduledToken: String?, due: Long, now: Long): Boolean =
        token.isNotEmpty() && token == scheduledToken && due > 0 && now >= due

    fun next(now: ZonedDateTime, settings: DrinkSettings, reachedGoal: Boolean): ZonedDateTime {
        val today = now.toLocalDate()
        fun at(minutes: Int, dayOffset: Long = 0) = today.plusDays(dayOffset)
            .atTime(minutes / 60, minutes % 60).atZone(now.zone)
        if (reachedGoal && settings.stopAtGoal) return at(settings.start, 1)
        if (now < at(settings.start)) return at(settings.start)
        val candidate = now.plusMinutes(settings.interval.toLong())
        return if (candidate <= at(settings.end)) candidate else at(settings.start, 1)
    }

    fun mayNotify(now: ZonedDateTime, settings: DrinkSettings, reachedGoal: Boolean): Boolean {
        val minutes = now.hour * 60 + now.minute
        return settings.enabled && minutes in settings.start..settings.end && !(settings.stopAtGoal && reachedGoal)
    }
}
