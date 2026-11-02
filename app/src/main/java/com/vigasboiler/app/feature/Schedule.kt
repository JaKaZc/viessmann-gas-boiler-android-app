package com.vigasboiler.app.feature

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

enum class Day(val key: String) { MON("mon"), TUE("tue"), WED("wed"), THU("thu"), FRI("fri"), SAT("sat"), SUN("sun") }

/** One switching period. Times are `HH:mm`; `24:00` is a valid end. Outside periods `defaultMode` applies. */
data class ScheduleEntry(val mode: String, val start: String, val end: String, val position: Int = 0)

data class Schedule(val days: Map<Day, List<ScheduleEntry>>) {
    fun day(day: Day): List<ScheduleEntry> = days[day].orEmpty()

    fun withDay(day: Day, entries: List<ScheduleEntry>): Schedule = Schedule(days + (day to normalized(entries)))

    /** Copies [from] onto [to], replacing whatever [to] had. */
    fun copyDay(from: Day, to: Day): Schedule = withDay(to, day(from))

    /** Copies [from] onto every other day. */
    fun copyToAll(from: Day): Schedule = Day.entries.fold(this) { acc, d -> if (d == from) acc else acc.copyDay(from, d) }

    fun toJson(): JsonObject = buildJsonObject {
        Day.entries.forEach { d ->
            put(d.key, buildJsonArray {
                normalized(day(d)).forEach { e ->
                    add(buildJsonObject {
                        put("mode", e.mode)
                        put("start", e.start)
                        put("end", e.end)
                        put("position", e.position)
                    })
                }
            })
        }
    }

    companion object {
        val EMPTY = Schedule(Day.entries.associateWith { emptyList() })

        /** Sorted by start time with positions 0..n-1, as the API expects. */
        fun normalized(entries: List<ScheduleEntry>): List<ScheduleEntry> =
            entries.sortedBy { minutesOf(it.start) ?: Int.MAX_VALUE }.mapIndexed { i, e -> e.copy(position = i) }

        fun fromJson(json: JsonElement?): Schedule? {
            val obj = json as? JsonObject ?: return null
            return Schedule(Day.entries.associateWith { d ->
                (obj[d.key] as? JsonArray).orEmpty().mapNotNull { el ->
                    val e = el as? JsonObject ?: return@mapNotNull null
                    ScheduleEntry(
                        mode = e.str("mode") ?: return@mapNotNull null,
                        start = e.str("start") ?: return@mapNotNull null,
                        end = e.str("end") ?: return@mapNotNull null,
                        position = (e["position"] as? JsonPrimitive)?.intOrNull ?: 0,
                    )
                }
            })
        }

        /** Minutes since midnight for `HH:mm`, allowing `24:00`. Null when malformed. */
        fun minutesOf(time: String): Int? {
            val m = TIME.matchEntire(time) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (min > 59 || h > 24 || (h == 24 && min != 0)) return null
            return h * 60 + min
        }

        fun format(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

        private val TIME = Regex("(\\d{2}):(\\d{2})")
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

data class ScheduleViolation(val day: Day, val index: Int?, val kind: Kind, val detail: String = "") {
    enum class Kind { TooManyEntries, ModeNotAllowed, BadTime, OffResolution, StartNotBeforeEnd, Overlap }
}

/** Rules taken from the `Schedule` param constraints of `setSchedule`. */
data class ScheduleRules(
    val modes: List<String>,
    val maxEntries: Int?,
    val resolutionMinutes: Int?,
    val overlapAllowed: Boolean,
    val defaultMode: String?,
) {
    fun validate(schedule: Schedule): List<ScheduleViolation> = Day.entries.flatMap { validateDay(it, schedule.day(it)) }

    fun validateDay(day: Day, entries: List<ScheduleEntry>): List<ScheduleViolation> {
        val out = mutableListOf<ScheduleViolation>()
        if (maxEntries != null && entries.size > maxEntries) {
            out += ScheduleViolation(day, null, ScheduleViolation.Kind.TooManyEntries, "${entries.size} > $maxEntries")
        }
        entries.forEachIndexed { i, e ->
            if (modes.isNotEmpty() && e.mode !in modes) out += ScheduleViolation(day, i, ScheduleViolation.Kind.ModeNotAllowed, e.mode)
            val start = Schedule.minutesOf(e.start)
            val end = Schedule.minutesOf(e.end)
            if (start == null || start >= 24 * 60) out += ScheduleViolation(day, i, ScheduleViolation.Kind.BadTime, e.start)
            if (end == null) out += ScheduleViolation(day, i, ScheduleViolation.Kind.BadTime, e.end)
            resolutionMinutes?.takeIf { it > 0 }?.let { res ->
                listOfNotNull(start, end).filter { it % res != 0 }.forEach {
                    out += ScheduleViolation(day, i, ScheduleViolation.Kind.OffResolution, Schedule.format(it))
                }
            }
            if (start != null && end != null && start >= end) {
                out += ScheduleViolation(day, i, ScheduleViolation.Kind.StartNotBeforeEnd, "${e.start}-${e.end}")
            }
        }
        if (!overlapAllowed) {
            val sorted = entries.mapIndexedNotNull { i, e ->
                val s = Schedule.minutesOf(e.start) ?: return@mapIndexedNotNull null
                val en = Schedule.minutesOf(e.end) ?: return@mapIndexedNotNull null
                Triple(i, s, en)
            }.sortedBy { it.second }
            sorted.zipWithNext().forEach { (a, b) ->
                // Touching periods (a ends exactly when b starts) are fine.
                if (b.second < a.third) out += ScheduleViolation(day, b.first, ScheduleViolation.Kind.Overlap, "with #${a.first}")
            }
        }
        return out
    }

    companion object {
        fun from(c: Constraints) = ScheduleRules(
            modes = c.modes.orEmpty(),
            maxEntries = c.maxEntries,
            resolutionMinutes = c.resolution,
            overlapAllowed = c.overlapAllowed ?: true,
            defaultMode = c.defaultMode,
        )
    }
}
