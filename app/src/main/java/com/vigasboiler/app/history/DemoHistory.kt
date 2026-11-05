package com.vigasboiler.app.history

import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random
import com.vigasboiler.app.feature.FeatureSnapshot

/** A history value at a point in time. */
data class TimedValue(val timestampMillis: Long, val value: HistoryValue)

/**
 * A plausible week of 30-minute snapshots before [FeatureSnapshot.fetchedAtMillis] for demo mode,
 * so charts, statistics and the heating check have something to show. Counters (burner hours,
 * starts, gas today) end at the snapshot's own values, so the first real snapshot continues them.
 */
object DemoHistory {
    const val STEP_MILLIS = 30 * 60_000L
    const val STEPS = 7 * 48 + 12

    private const val BURNER = "heating.burners.0"
    private const val MODULATION = "heating.burners.0.modulation"
    private const val STATISTICS = "heating.burners.0.statistics"
    private const val ROOM = "heating.circuits.0.sensors.temperature.room"
    private const val OUTSIDE = "heating.sensors.temperature.outside"
    private const val SUPPLY = "heating.boiler.sensors.temperature.commonSupply"
    private const val PRESSURE = "heating.sensors.pressure.supply"
    private const val GAS = "heating.gas.consumption.summary.heating"
    private const val PROGRAMS = "heating.circuits.0.operating.programs"

    private class Step(val t: Long, val hour: Double, val day: java.time.LocalDate, val reduced: Boolean, val on: Boolean, val modulation: Double, val start: Boolean)

    fun week(snapshot: FeatureSnapshot, zone: ZoneId, random: Random = Random(7)): List<TimedValue> {
        val end = snapshot.fetchedAtMillis
        val steps = (STEPS downTo 1).map { i ->
            val t = end - i * STEP_MILLIS
            val time = Instant.ofEpochMilli(t).atZone(zone)
            val hour = time.hour + time.minute / 60.0
            val reduced = hour >= 22 || hour < 6
            val on = random.nextDouble() < if (reduced) 0.3 else 0.55
            Step(t, hour, time.toLocalDate(), reduced, on, if (on) random.nextDouble(12.0, 28.0) else 0.0, on && random.nextDouble() < 0.9)
        }
        // Counted backwards from the snapshot so the series run into its values.
        var hours = snapshot[STATISTICS]?.number("hours") ?: 8000.0
        var starts = snapshot[STATISTICS]?.number("starts") ?: 3600.0
        val hoursAt = DoubleArray(steps.size)
        val startsAt = DoubleArray(steps.size)
        for (i in steps.indices.reversed()) {
            hoursAt[i] = floor(hours)
            startsAt[i] = starts
            if (steps[i].on) hours -= 0.5
            if (steps[i].start) starts -= 1
        }
        val gasAt = gasToday(steps, snapshot[GAS]?.number("currentDay"), snapshot.fetchedAtMillis, zone)
        val normal = snapshot["$PROGRAMS.normal"]?.number("temperature") ?: 21.0
        val reducedTemp = snapshot["$PROGRAMS.reduced"]?.number("temperature") ?: 19.0
        val comfort = snapshot["$PROGRAMS.comfort"]?.number("temperature") ?: 22.0

        return steps.flatMapIndexed { i, s ->
            val outside = 6 + 4 * sin((s.hour - 9) / 24 * 2 * PI) + random.nextDouble(-1.0, 1.0)
            val room = (if (s.reduced) reducedTemp + 0.2 else normal - 0.7) + random.nextDouble(-0.15, 0.15)
            val supply = if (s.on) 42 + (8 - outside) * 1.2 + random.nextDouble(-2.0, 2.0) else 30 + random.nextDouble(-2.0, 2.0)
            listOf(
                HistoryValue(BURNER, "active", if (s.on) 1.0 else 0.0, null),
                HistoryValue(MODULATION, "value", s.modulation, "percent"),
                HistoryValue(STATISTICS, "hours", hoursAt[i], "hour"),
                HistoryValue(STATISTICS, "starts", startsAt[i], ""),
                HistoryValue(ROOM, "value", room, "celsius"),
                HistoryValue(OUTSIDE, "value", outside, "celsius"),
                HistoryValue(SUPPLY, "value", supply, "celsius"),
                HistoryValue(PRESSURE, "value", 1.55 - 0.1 * i / steps.size, "bar"),
                HistoryValue(GAS, "currentDay", gasAt[i], "cubicMeter"),
                HistoryValue("$PROGRAMS.normal", "active", if (s.reduced) 0.0 else 1.0, null),
                HistoryValue("$PROGRAMS.normal", "temperature", normal, "celsius"),
                HistoryValue("$PROGRAMS.reduced", "active", if (s.reduced) 1.0 else 0.0, null),
                HistoryValue("$PROGRAMS.reduced", "temperature", reducedTemp, "celsius"),
                HistoryValue("$PROGRAMS.comfort", "active", 0.0, null),
                HistoryValue("$PROGRAMS.comfort", "temperature", comfort, "celsius"),
            ).map { TimedValue(s.t, it) }
        }
    }

    /**
     * The daily gas counter: grows with modulation and restarts at midnight. Today's part is
     * scaled to stay below the snapshot's value so the counter never runs backwards.
     */
    private fun gasToday(steps: List<Step>, todayEnd: Double?, endMillis: Long, zone: ZoneId): DoubleArray {
        val gas = DoubleArray(steps.size)
        var total = 0.0
        var day: java.time.LocalDate? = null
        steps.forEachIndexed { i, s ->
            if (s.day != day) { total = 0.0; day = s.day }
            if (s.on) total += s.modulation / 100 * 0.45
            gas[i] = total
        }
        val today = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDate()
        val todayMax = steps.indices.filter { steps[it].day == today }.maxOfOrNull { gas[it] } ?: return gas.round()
        if (todayEnd != null && todayMax > 0) {
            val scale = minOf(1.0, todayEnd * 0.95 / todayMax)
            steps.indices.filter { steps[it].day == today }.forEach { gas[it] *= scale }
        }
        return gas.round()
    }

    private fun DoubleArray.round() = apply { indices.forEach { this[it] = Math.round(this[it] * 100) / 100.0 } }
}
