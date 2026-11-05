package com.vigasboiler.app.feature

import kotlin.math.abs
import kotlin.math.roundToInt

/** Which part of a period a finger holds on the 24 h ring. */
enum class DialPart { Start, End, Body }

data class DialGrab(val index: Int, val part: DialPart)

/**
 * Moving periods on the 24 h ring. A drag always starts from the entries as they were at touch
 * down and adds the minutes the finger has travelled, so a handle held at a limit stays there and
 * picks up again when the finger comes back.
 *
 * Periods never cross midnight and never overlap: a handle stops at the neighbouring period. Two
 * periods that touch share their boundary, so dragging it moves both.
 */
object ScheduleDrag {
    private const val DAY = 24 * 60
    private const val NEW_PERIOD_MINUTES = 3 * 60

    /**
     * What a touch at [minute] holds: the nearest handle within [toleranceMinutes], else the
     * period under the finger. Several results when handles lie on top of each other (for
     * example 00:00 and 24:00); [pick] decides by the drag's direction.
     */
    fun hit(entries: List<ScheduleEntry>, minute: Double, toleranceMinutes: Double): List<DialGrab> {
        val handles = entries.flatMapIndexed { i, e ->
            val s = Schedule.minutesOf(e.start) ?: return@flatMapIndexed emptyList()
            val en = Schedule.minutesOf(e.end) ?: return@flatMapIndexed emptyList()
            listOf(Triple(i, DialPart.Start, ringDistance(minute, s.toDouble())), Triple(i, DialPart.End, ringDistance(minute, en.toDouble())))
        }.filter { it.third <= toleranceMinutes }
        val nearest = handles.minOfOrNull { it.third }
        if (nearest != null) return handles.filter { it.third <= nearest + 1 }.map { DialGrab(it.first, it.second) }
        val body = entries.indexOfFirst { e ->
            val s = Schedule.minutesOf(e.start)
            val en = Schedule.minutesOf(e.end)
            s != null && en != null && minute >= s && minute < en
        }
        return if (body >= 0) listOf(DialGrab(body, DialPart.Body)) else emptyList()
    }

    /** The first of [candidates] that can move in the drag's direction. */
    fun pick(entries: List<ScheduleEntry>, candidates: List<DialGrab>, deltaMinutes: Int, resolution: Int): DialGrab =
        candidates.firstOrNull { apply(entries, it, deltaMinutes, resolution) != entries } ?: candidates.first()

    /** [entries] with the grabbed part moved by [deltaMinutes], snapped to [resolution]. */
    fun apply(entries: List<ScheduleEntry>, grab: DialGrab, deltaMinutes: Int, resolution: Int): List<ScheduleEntry> {
        val entry = entries.getOrNull(grab.index) ?: return entries
        val start = Schedule.minutesOf(entry.start) ?: return entries
        val end = Schedule.minutesOf(entry.end) ?: return entries
        if (deltaMinutes == 0) return entries
        val step = resolution.coerceAtLeast(1)
        val others = entries.withIndex().filter { it.index != grab.index }.mapNotNull { (i, e) ->
            val s = Schedule.minutesOf(e.start) ?: return@mapNotNull null
            val en = Schedule.minutesOf(e.end) ?: return@mapNotNull null
            Span(i, s, en)
        }
        val before = others.filter { it.end <= start }.maxByOrNull { it.end }
        val after = others.filter { it.start >= end }.minByOrNull { it.start }
        val floor = before?.end ?: 0
        val ceiling = after?.start ?: DAY
        fun snap(minutes: Int) = (minutes / step.toDouble()).roundToInt() * step

        val result = entries.toMutableList()
        fun set(index: Int, from: Int? = null, to: Int? = null) {
            val e = result[index]
            result[index] = e.copy(start = from?.let(Schedule::format) ?: e.start, end = to?.let(Schedule::format) ?: e.end)
        }
        when (grab.part) {
            DialPart.Start -> {
                val touching = before?.takeIf { it.end == start }
                val moved = clamp(snap(start + deltaMinutes), (touching?.let { it.start + step }) ?: floor, end - step)
                set(grab.index, from = moved)
                touching?.let { set(it.index, to = moved) }
            }
            DialPart.End -> {
                val touching = after?.takeIf { it.start == end }
                val moved = clamp(snap(end + deltaMinutes), start + step, (touching?.let { it.end - step }) ?: ceiling)
                set(grab.index, to = moved)
                touching?.let { set(it.index, from = moved) }
            }
            DialPart.Body -> {
                val length = end - start
                val moved = clamp(snap(start + deltaMinutes), floor, ceiling - length)
                set(grab.index, from = moved, to = moved + length)
            }
        }
        return result
    }

    /** Up to [NEW_PERIOD_MINUTES] in the middle of the day's largest gap; null when it has no room. */
    fun freeSlot(entries: List<ScheduleEntry>, resolution: Int): Pair<Int, Int>? {
        val step = resolution.coerceAtLeast(1)
        val taken = entries.mapNotNull { e ->
            val s = Schedule.minutesOf(e.start) ?: return@mapNotNull null
            val en = Schedule.minutesOf(e.end) ?: return@mapNotNull null
            s to en
        }.sortedBy { it.first }
        val edges = listOf(0 to 0) + taken + listOf(DAY to DAY)
        val (from, to) = edges.zipWithNext { a, b -> a.second to b.first }.maxByOrNull { it.second - it.first } ?: return null
        val gap = to - from
        if (gap < step) return null
        val length = minOf(gap, NEW_PERIOD_MINUTES) / step * step
        val start = maxOf(from, (from + (gap - length) / 2) / step * step)
        return start to start + length
    }

    /** Shortest way round the ring from [from] to [to], in minutes (negative: counter-clockwise). */
    fun ringDelta(from: Double, to: Double): Double {
        val d = (to - from).mod(DAY.toDouble())
        return if (d > DAY / 2) d - DAY else d
    }

    private fun ringDistance(a: Double, b: Double) = abs(ringDelta(a, b))

    /** Like coerceIn, but an impossible range (no room to move) keeps the lower bound. */
    private fun clamp(value: Int, min: Int, max: Int) = if (max < min) min else value.coerceIn(min, max)

    private data class Span(val index: Int, val start: Int, val end: Int)
}
