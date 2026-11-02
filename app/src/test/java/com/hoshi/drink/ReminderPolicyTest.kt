package com.hoshi.drink

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class ReminderPolicyTest {
    private val settings = DrinkSettings(enabled = true)
    private fun at(value: String) = ZonedDateTime.parse("2026-11-02T${value}:00+08:00[Asia/Shanghai]")

    @Test fun onlyCurrentDueOccurrenceCanDeliver() {
        assertTrue(ReminderPolicy.shouldDeliver("current", "current", 1000, 1000))
        assertTrue(ReminderPolicy.shouldDeliver("current", "current", 1000, 5000))
        assertFalse(ReminderPolicy.shouldDeliver("current", "current", 1000, 999))
    }
    @Test fun replacedAndConsumedOccurrencesCannotDeliver() {
        assertFalse(ReminderPolicy.shouldDeliver("old", "current", 1000, 5000))
        assertFalse(ReminderPolicy.shouldDeliver("current", null, 0, 5000))
        assertFalse(ReminderPolicy.shouldDeliver("", "", 1000, 5000))
    }

    @Test fun beforeWindowSchedulesStart() {
        assertEquals(at("08:00"), ReminderPolicy.next(at("06:30"), settings, false))
    }
    @Test fun withinWindowUsesInterval() {
        assertEquals(at("13:30"), ReminderPolicy.next(at("12:30"), settings, false))
    }
    @Test fun intervalEndingAtBoundaryIsAllowed() {
        assertEquals(at("22:00"), ReminderPolicy.next(at("21:00"), settings, false))
    }
    @Test fun intervalBeyondEndMovesToTomorrow() {
        assertEquals(at("08:00").plusDays(1), ReminderPolicy.next(at("21:30"), settings, false))
    }
    @Test fun afterWindowMovesToTomorrow() {
        assertEquals(at("08:00").plusDays(1), ReminderPolicy.next(at("23:40"), settings, false))
    }
    @Test fun goalReachedPausesUntilTomorrow() {
        assertEquals(at("08:00").plusDays(1), ReminderPolicy.next(at("10:00"), settings, true))
        assertFalse(ReminderPolicy.mayNotify(at("10:00"), settings, true))
    }
    @Test fun optionalGoalPauseCanBeDisabled() {
        val continuous = settings.copy(stopAtGoal = false)
        assertEquals(at("11:00"), ReminderPolicy.next(at("10:00"), continuous, true))
        assertTrue(ReminderPolicy.mayNotify(at("10:00"), continuous, true))
    }
    @Test fun delayedWorkerCannotNotifyOutsideWindow() {
        assertFalse(ReminderPolicy.mayNotify(at("07:59"), settings, false))
        assertFalse(ReminderPolicy.mayNotify(at("22:01"), settings, false))
        assertTrue(ReminderPolicy.mayNotify(at("08:00"), settings, false))
        assertTrue(ReminderPolicy.mayNotify(at("22:00"), settings, false))
    }
    @Test fun disabledRemindersNeverNotify() {
        assertFalse(ReminderPolicy.mayNotify(at("12:00"), settings.copy(enabled = false), false))
    }
    @Test fun nextDayStartKeepsLocalTimeAcrossDaylightSaving() {
        val now = ZonedDateTime.parse("2026-10-31T23:00:00-04:00[America/New_York]")
        val next = ReminderPolicy.next(now, settings, false)
        assertEquals(8, next.hour)
        assertEquals(1, next.dayOfMonth)
        assertEquals(-5 * 3600, next.offset.totalSeconds)
    }
}
