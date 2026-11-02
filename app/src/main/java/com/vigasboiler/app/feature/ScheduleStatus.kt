package com.vigasboiler.app.feature

/**
 * What the schedule says right now: the active [mode], when it ends and what follows.
 * [untilMinute] is minutes since midnight of the day [untilDayOffset] days ahead (0 = today);
 * null when the mode never changes (an empty schedule).
 */
data class ScheduleStatus(val mode: String, val untilMinute: Int?, val untilDayOffset: Int, val nextMode: String?)

object ScheduleNow {

    fun status(schedule: Schedule, defaultMode: String, day: Day, minute: Int): ScheduleStatus {
        val today = periods(schedule, day)
        today.firstOrNull { minute >= it.first && minute < it.second }?.let { (_, end, mode) ->
            // A period running to midnight continues if tomorrow starts at 00:00.
            if (end == DAY_MINUTES) {
                val tomorrow = periods(schedule, day.plus(1)).firstOrNull()
                if (tomorrow != null && tomorrow.first == 0) return ScheduleStatus(mode, tomorrow.second, 1, defaultMode)
                return ScheduleStatus(mode, 0, 1, defaultMode)
            }
            val adjoining = today.firstOrNull { it.first == end }
            return ScheduleStatus(mode, end, 0, adjoining?.third ?: defaultMode)
        }
        // Default mode until the next period starts, today or on a following day.
        for (offset in 0..7) {
            val next = periods(schedule, day.plus(offset)).firstOrNull { offset > 0 || it.first > minute }
            if (next != null) return ScheduleStatus(defaultMode, next.first, offset, next.third)
        }
        return ScheduleStatus(defaultMode, null, 0, null)
    }

    /** (start, end, mode) in minutes, sorted, malformed entries skipped. */
    private fun periods(schedule: Schedule, day: Day): List<Triple<Int, Int, String>> =
        schedule.day(day).mapNotNull { e ->
            val s = Schedule.minutesOf(e.start) ?: return@mapNotNull null
            val en = Schedule.minutesOf(e.end) ?: return@mapNotNull null
            if (en > s) Triple(s, en, e.mode) else null
        }.sortedBy { it.first }

    private fun Day.plus(days: Int): Day = Day.entries[(ordinal + days) % Day.entries.size]

    private const val DAY_MINUTES = 24 * 60
}
