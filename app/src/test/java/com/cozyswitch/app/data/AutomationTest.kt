package com.cozyswitch.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AutomationTest {
    private val oslo = ZoneId.of("Europe/Oslo")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, oslo)
    // 2026-11-09 is a Monday
    private val monMorning = at(2026, 11, 9, 6, 0)
    private val a = Automation(hour = 7, minute = 30, plugs = setOf("p1"))

    @Test fun laterTodayRunsToday() = assertEquals(at(2026, 11, 9, 7, 30), a.next(monMorning))

    @Test fun passedTodayRunsTomorrow() = assertEquals(at(2026, 11, 10, 7, 30), a.next(at(2026, 11, 9, 7, 30)))

    @Test fun weekdaysSkipTheWeekend() {
        val wd = a.copy(days = setOf(1, 2, 3, 4, 5))
        assertEquals(at(2026, 11, 16, 7, 30), wd.next(at(2026, 11, 13, 8, 0))) // Fri after → Mon
    }

    @Test fun onlySunday() = assertEquals(at(2026, 11, 15, 7, 30), a.copy(days = setOf(7)).next(monMorning))

    @Test fun onceRunsAtTheNextTime() = assertEquals(at(2026, 11, 10, 7, 30), a.copy(days = emptySet()).next(at(2026, 11, 9, 9, 0)))

    @Test fun disabledOrEmptyNeverRuns() {
        assertNull(a.copy(enabled = false).next(monMorning))
        assertNull(a.copy(plugs = emptySet()).next(monMorning))
    }

    @Test fun summerTimeChangeKeepsTheLocalTime() {
        // 2027-03-28 clocks go forward in Norway; 07:30 is still 07:30 local.
        assertEquals(at(2027, 3, 28, 7, 30), a.next(at(2027, 3, 27, 22, 0).plusHours(1)))
    }

    @Test fun daysText() {
        assertEquals("Every day", a.daysText())
        assertEquals("Weekdays", a.copy(days = setOf(1, 2, 3, 4, 5)).daysText())
        assertEquals("Once", a.copy(days = emptySet()).daysText())
        assertEquals("Mon Wed", a.copy(days = setOf(3, 1)).daysText())
    }

    @Test fun savesAndLoadsTheSame() {
        val x = Automation(name = "Heater", on = false, hour = 22, minute = 5, days = setOf(6, 7), plugs = setOf("a", "b"), enabled = false)
        assertEquals(x, Automations.fromJson(JSONObject(Automations.toJson(x).toString())))
    }
}
