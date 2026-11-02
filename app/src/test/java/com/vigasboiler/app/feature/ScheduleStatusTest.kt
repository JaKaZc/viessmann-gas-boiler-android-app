package com.vigasboiler.app.feature

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleStatusTest {

    // The real schedule: weekdays 15:00–20:30 normal, weekend 07:00–21:00 normal.
    private val real = Schedule.fromJson(
        (Fixtures.boiler("heating.circuits.0.heating.schedule").properties["entries"] as PropertyValue.ObjectValue).value,
    )!!

    private fun at(day: Day, time: String) = ScheduleNow.status(real, "reduced", day, Schedule.minutesOf(time)!!)

    @Test
    fun `inside a period it runs until the period ends`() {
        assertEquals(ScheduleStatus("normal", Schedule.minutesOf("20:30"), 0, "reduced"), at(Day.MON, "16:00"))
    }

    @Test
    fun `before the first period the default mode runs until it starts`() {
        assertEquals(ScheduleStatus("reduced", Schedule.minutesOf("15:00"), 0, "normal"), at(Day.WED, "06:00"))
    }

    @Test
    fun `after the last period the next one is found on a following day`() {
        // Friday 22:00 → Saturday 07:00.
        assertEquals(ScheduleStatus("reduced", Schedule.minutesOf("07:00"), 1, "normal"), at(Day.FRI, "22:00"))
        // Sunday 22:00 wraps to Monday 15:00.
        assertEquals(ScheduleStatus("reduced", Schedule.minutesOf("15:00"), 1, "normal"), at(Day.SUN, "22:00"))
    }

    @Test
    fun `adjoining periods hand over to the next mode`() {
        val s = Schedule.EMPTY.withDay(Day.TUE, listOf(ScheduleEntry("normal", "06:00", "08:00"), ScheduleEntry("comfort", "08:00", "10:00")))
        assertEquals(ScheduleStatus("normal", 8 * 60, 0, "comfort"), ScheduleNow.status(s, "reduced", Day.TUE, 7 * 60))
    }

    @Test
    fun `a period to midnight continues into one starting at midnight`() {
        val s = Schedule.EMPTY
            .withDay(Day.TUE, listOf(ScheduleEntry("normal", "22:00", "24:00")))
            .withDay(Day.WED, listOf(ScheduleEntry("normal", "00:00", "02:00")))
        assertEquals(ScheduleStatus("normal", 2 * 60, 1, "reduced"), ScheduleNow.status(s, "reduced", Day.TUE, 23 * 60))
    }

    @Test
    fun `an empty schedule never changes`() {
        assertEquals(ScheduleStatus("reduced", null, 0, null), ScheduleNow.status(Schedule.EMPTY, "reduced", Day.MON, 600))
    }
}
