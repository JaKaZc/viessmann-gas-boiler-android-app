package com.vigasboiler.app.analysis

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import com.vigasboiler.app.data.HistoryRow

/**
 * "Heating check": reads a week of stored snapshots plus the current settings and rates burner
 * cycling, comfort, efficiency and the system, with suggestions. Pure Kotlin so every rule is
 * unit-tested; thresholds are common rules of thumb for gas condensing boilers, not Viessmann
 * specifications, and the UI words suggestions as such.
 */
object HeatingCheck {
    const val REQUIRED_DAYS = 7
    /** A day counts when it has at least this many snapshots (6 hours at the default 30 min). */
    const val MIN_SAMPLES_PER_DAY = 12
    const val WINDOW_DAYS = 7L
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    /** History series the check reads; all values of one snapshot share a timestamp. */
    enum class Series(val feature: String, val property: String) {
        BurnerActive("heating.burners.0", "active"),
        Modulation("heating.burners.0.modulation", "value"),
        Hours("heating.burners.0.statistics", "hours"),
        Starts("heating.burners.0.statistics", "starts"),
        Room("heating.circuits.0.sensors.temperature.room", "value"),
        Outside("heating.sensors.temperature.outside", "value"),
        Supply("heating.boiler.sensors.temperature.commonSupply", "value"),
        Pressure("heating.sensors.pressure.supply", "value"),
        GasToday("heating.gas.consumption.summary.heating", "currentDay"),
        NormalActive("heating.circuits.0.operating.programs.normal", "active"),
        NormalTemp("heating.circuits.0.operating.programs.normal", "temperature"),
        ComfortActive("heating.circuits.0.operating.programs.comfort", "active"),
        ComfortTemp("heating.circuits.0.operating.programs.comfort", "temperature"),
        ReducedActive("heating.circuits.0.operating.programs.reduced", "active"),
        ReducedTemp("heating.circuits.0.operating.programs.reduced", "temperature"),
    }

    /** Current settings from the latest snapshot and the app. */
    data class Settings(
        val slope: Double?,
        val shift: Double?,
        val normal: Double?,
        val reduced: Double?,
        val comfort: Double?,
        val pressureMin: Double,
        val pressureMax: Double,
        val intervalMinutes: Int,
    )

    // --- Readiness ------------------------------------------------------------------------

    data class Readiness(val days: Int, val required: Int = REQUIRED_DAYS) {
        val ready: Boolean get() = days >= required
        val missing: Int get() = (required - days).coerceAtLeast(0)
    }

    /** Days among the last 14 with enough snapshots; [timestamps] are snapshot times. */
    fun readiness(timestamps: List<Long>, zone: ZoneId, today: LocalDate): Readiness {
        val since = today.minusDays(13)
        val perDay = timestamps.groupingBy { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.eachCount()
        return Readiness(perDay.count { (day, n) -> !day.isBefore(since) && !day.isAfter(today) && n >= MIN_SAMPLES_PER_DAY })
    }

    // --- Report ---------------------------------------------------------------------------

    enum class Rating { Good, Tip, Warning, Info }

    /** A suggested heating-curve change, already clamped to the command's ranges. */
    data class CurveChange(val slopeFrom: Double, val slopeTo: Double, val shiftFrom: Double, val shiftTo: Double)

    sealed interface Finding {
        val rating: Rating

        /** Starts per burner hour and per day from the lifetime counters. */
        data class Cycling(override val rating: Rating, val startsPerHour: Double, val startsPerDay: Double, val hoursPerDay: Double) : Finding
        data class Modulation(override val rating: Rating, val average: Double, val lowShare: Double) : Finding
        /** Room minus target while Normal or Comfort ran (warm-up after a switch excluded). */
        data class RoomVsTarget(
            override val rating: Rating,
            val deviation: Double,
            val coldDeviation: Double?,
            val mildDeviation: Double?,
            val curve: CurveChange?,
        ) : Finding
        data object NoRoomSensor : Finding { override val rating = Rating.Info }
        data class ComfortBelowNormal(override val rating: Rating, val comfort: Double, val normal: Double) : Finding
        data class Setback(override val rating: Rating, val normal: Double, val reduced: Double, val reducedShare: Double?) : Finding
        /** Average boiler supply temperature while the burner ran. */
        data class SupplyTemperature(override val rating: Rating, val average: Double, val max: Double) : Finding
        /** Burner running although it was mild outside. */
        data class HeatingLimit(override val rating: Rating, val mildShare: Double, val burnerShareWhenMild: Double) : Finding
        data class Pressure(
            override val rating: Rating,
            val min: Double,
            val max: Double,
            val drop: Double,
            val limitMin: Double,
            val limitMax: Double,
        ) : Finding
        data class Gas(override val rating: Rating, val cubicMeters: Double, val perDegreeDay: Double?) : Finding
        /** How much of the expected background data arrived. */
        data class Coverage(override val rating: Rating, val share: Double, val samples: Int) : Finding
    }

    enum class Section { Burner, Comfort, Efficiency, System }

    data class Report(
        val fromMillis: Long,
        val toMillis: Long,
        val samples: Int,
        val sections: Map<Section, List<Finding>>,
    ) {
        val findings: List<Finding> get() = sections.values.flatten()
        fun count(rating: Rating) = findings.count { it.rating == rating }
    }

    fun analyze(data: Map<Series, List<HistoryRow>>, settings: Settings, zone: ZoneId, nowMillis: Long): Report {
        val from = nowMillis - WINDOW_DAYS * DAY
        val s = data.mapValues { (_, rows) -> rows.filter { it.timestampMillis in from..nowMillis }.sortedBy { it.timestampMillis } }
        fun rows(series: Series) = s[series].orEmpty()
        fun byTime(series: Series) = rows(series).associate { it.timestampMillis to it.value }
        val times = rows(Series.BurnerActive).map { it.timestampMillis }

        val burner = listOfNotNull(cycling(rows(Series.Hours), rows(Series.Starts)), modulation(byTime(Series.BurnerActive), rows(Series.Modulation)))
        val comfort = listOfNotNull(roomVsTarget(s, settings), comfortOrder(settings))
        val efficiency = listOfNotNull(
            supply(byTime(Series.BurnerActive), rows(Series.Supply)),
            setback(settings, byTime(Series.ReducedActive)),
            heatingLimit(byTime(Series.BurnerActive), rows(Series.Outside)),
            gas(rows(Series.GasToday), rows(Series.Outside), zone),
        )
        val system = listOfNotNull(pressure(rows(Series.Pressure), settings), coverage(times.size, settings.intervalMinutes))
        return Report(
            fromMillis = times.firstOrNull() ?: from,
            toMillis = times.lastOrNull() ?: nowMillis,
            samples = times.size,
            sections = linkedMapOf(Section.Burner to burner, Section.Comfort to comfort, Section.Efficiency to efficiency, Section.System to system),
        )
    }

    // --- Rules ----------------------------------------------------------------------------

    /**
     * Short cycling: many starts per hour of burning wear the boiler and waste gas. Rule of thumb
     * for condensing boilers: up to ~1.5 starts per burner hour is fine, above 3 is short cycling.
     */
    internal fun cycling(hours: List<HistoryRow>, starts: List<HistoryRow>): Finding.Cycling? {
        if (hours.size < 2 || starts.size < 2) return null
        val dh = increase(hours)
        val ds = increase(starts)
        val days = (hours.last().timestampMillis - hours.first().timestampMillis).toDouble() / DAY
        if (dh < 3 || days < 1) return null
        val perHour = ds / dh
        val perDay = ds / days
        val rating = when {
            perHour > 3 || perDay > 40 -> Rating.Warning
            perHour > 1.5 || perDay > 20 -> Rating.Tip
            else -> Rating.Good
        }
        return Finding.Cycling(rating, perHour, perDay, dh / days)
    }

    /** Sum of a lifetime counter's increases; a drop (replaced control unit, reset) is skipped. */
    private fun increase(counter: List<HistoryRow>): Double =
        counter.zipWithNext { a, b -> (b.value - a.value).coerceAtLeast(0.0) }.sum()

    /** Running mostly at minimum power means demand is below the boiler's smallest output. */
    internal fun modulation(burnerOn: Map<Long, Double>, modulation: List<HistoryRow>): Finding.Modulation? {
        val running = modulation.filter { burnerOn[it.timestampMillis] == 1.0 && it.value > 0 }
        if (running.size < 12) return null
        val average = running.map { it.value }.average()
        val low = running.count { it.value <= LOW_MODULATION }.toDouble() / running.size
        return Finding.Modulation(if (low > 0.7) Rating.Tip else Rating.Good, average, low)
    }

    private const val LOW_MODULATION = 20.0

    /**
     * Room temperature against the active program's target. A deviation that grows when it is
     * cold outside points at the slope, one that is the same at all outside temperatures at the
     * shift. Warm-up and cool-down after a program change (2 h) are left out.
     */
    internal fun roomVsTarget(s: Map<Series, List<HistoryRow>>, settings: Settings): Finding? {
        val room = s[Series.Room].orEmpty().associate { it.timestampMillis to it.value }
        if (room.isEmpty()) return Finding.NoRoomSensor
        fun map(series: Series) = s[series].orEmpty().associate { it.timestampMillis to it.value }
        val outside = map(Series.Outside)
        val normalOn = map(Series.NormalActive); val normalT = map(Series.NormalTemp)
        val comfortOn = map(Series.ComfortActive); val comfortT = map(Series.ComfortTemp)
        val reducedOn = map(Series.ReducedActive)
        var lastProgram: String? = null
        var switchedAt = Long.MIN_VALUE
        val deviations = mutableListOf<Pair<Double, Double?>>() // deviation, outside
        for (t in room.keys.sorted()) {
            val program = when {
                comfortOn[t] == 1.0 -> "comfort"
                normalOn[t] == 1.0 -> "normal"
                reducedOn[t] == 1.0 -> "reduced"
                else -> null
            }
            if (program != lastProgram) { lastProgram = program; switchedAt = t }
            if (t - switchedAt < 2 * HOUR) continue
            val target = when (program) {
                "comfort" -> comfortT[t] ?: settings.comfort
                "normal" -> normalT[t] ?: settings.normal
                else -> null
            } ?: continue
            deviations += (room.getValue(t) - target) to outside[t]
        }
        if (deviations.size < 24) return null
        val deviation = deviations.map { it.first }.average()
        val cold = deviations.filter { (it.second ?: return@filter false) < COLD_OUTSIDE }.takeIf { it.size >= 24 }?.map { it.first }?.average()
        val mild = deviations.filter { (it.second ?: return@filter false) >= COLD_OUTSIDE }.takeIf { it.size >= 24 }?.map { it.first }?.average()
        val rating = when {
            deviation < -1.0 -> Rating.Warning
            abs(deviation) > 0.5 -> Rating.Tip
            cold != null && mild != null && abs(cold - mild) > 0.8 -> Rating.Tip
            else -> Rating.Good
        }
        val curve = if (rating == Rating.Good) null else curveChange(deviation, cold, mild, settings)
        return Finding.RoomVsTarget(rating, deviation, cold, mild, curve)
    }

    private const val COLD_OUTSIDE = 5.0

    /** Small steps (slope ±0.1, shift ±1 K), as Viessmann recommends: change, wait a day or two, check again. */
    internal fun curveChange(deviation: Double, cold: Double?, mild: Double?, settings: Settings): CurveChange? {
        val slope = settings.slope ?: return null
        val shift = settings.shift ?: return null
        val slopeDelta = if (cold != null && mild != null && abs(cold - mild) > 0.8) (if (cold < mild) 0.1 else -0.1) else 0.0
        val shiftDelta = when {
            slopeDelta != 0.0 -> 0.0
            deviation < -1.0 -> 2.0
            deviation < -0.5 -> 1.0
            deviation > 1.0 -> -2.0
            deviation > 0.5 -> -1.0
            else -> 0.0
        }
        if (slopeDelta == 0.0 && shiftDelta == 0.0) return null
        return CurveChange(slope, (slope + slopeDelta).coerceIn(0.2, 3.5), shift, (shift + shiftDelta).coerceIn(-13.0, 40.0))
    }

    internal fun comfortOrder(settings: Settings): Finding.ComfortBelowNormal? {
        val comfort = settings.comfort ?: return null
        val normal = settings.normal ?: return null
        return if (comfort < normal) Finding.ComfortBelowNormal(Rating.Info, comfort, normal) else null
    }

    /** Night setback: none wastes energy; more than ~4 K often costs more to recover than it saves. */
    internal fun setback(settings: Settings, reducedOn: Map<Long, Double>): Finding.Setback? {
        val normal = settings.normal ?: return null
        val reduced = settings.reduced ?: return null
        val diff = normal - reduced
        val share = reducedOn.values.takeIf { it.size >= 24 }?.let { v -> v.count { it == 1.0 }.toDouble() / v.size }
        val rating = when {
            diff < 1 -> Rating.Tip
            diff > 4 -> Rating.Tip
            else -> Rating.Good
        }
        return Finding.Setback(rating, normal, reduced, share)
    }

    /** Condensing needs a cool return; supply temperatures above ~55–60 °C mean little condensing. */
    internal fun supply(burnerOn: Map<Long, Double>, supply: List<HistoryRow>): Finding.SupplyTemperature? {
        val running = supply.filter { burnerOn[it.timestampMillis] == 1.0 }.map { it.value }
        if (running.size < 12) return null
        val average = running.average()
        val rating = when {
            average > 60 -> Rating.Warning
            average > 50 -> Rating.Tip
            else -> Rating.Good
        }
        return Finding.SupplyTemperature(rating, average, running.max())
    }

    /** Heating while it is mild outside (≥ 16 °C) usually means summer mode or a heating limit is missing. */
    internal fun heatingLimit(burnerOn: Map<Long, Double>, outside: List<HistoryRow>): Finding.HeatingLimit? {
        if (outside.isEmpty()) return null
        val mild = outside.filter { it.value >= MILD_OUTSIDE }
        if (mild.size < 24) return null
        val burning = mild.count { burnerOn[it.timestampMillis] == 1.0 }.toDouble() / mild.size
        return Finding.HeatingLimit(if (burning > 0.1) Rating.Tip else Rating.Good, mild.size.toDouble() / outside.size, burning)
    }

    private const val MILD_OUTSIDE = 16.0

    internal fun pressure(pressure: List<HistoryRow>, settings: Settings): Finding.Pressure? {
        if (pressure.size < 12) return null
        val values = pressure.map { it.value }
        val firstDay = pressure.filter { it.timestampMillis < pressure.first().timestampMillis + DAY }.map { it.value }.average()
        val lastDay = pressure.filter { it.timestampMillis > pressure.last().timestampMillis - DAY }.map { it.value }.average()
        val drop = firstDay - lastDay
        val rating = when {
            values.min() < settings.pressureMin || values.max() > settings.pressureMax -> Rating.Warning
            drop >= 0.2 -> Rating.Tip
            else -> Rating.Good
        }
        return Finding.Pressure(rating, values.min(), values.max(), drop, settings.pressureMin, settings.pressureMax)
    }

    /** Gas of the complete days, and per degree day (base 20 °C) to compare weeks with different weather. */
    internal fun gas(gasToday: List<HistoryRow>, outside: List<HistoryRow>, zone: ZoneId): Finding.Gas? {
        fun day(t: Long) = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
        val perDay = gasToday.groupBy { day(it.timestampMillis) }.mapValues { (_, v) -> v.maxOf { it.value } }
        if (perDay.isEmpty()) return null
        val total = perDay.values.sum()
        val degreeDays = outside.groupBy { day(it.timestampMillis) }
            .filterKeys { it in perDay.keys }
            .values.sumOf { v -> max(0.0, 20 - v.map { it.value }.average()) }
        return Finding.Gas(Rating.Info, total, (total / degreeDays).takeIf { degreeDays >= 5 })
    }

    /** Background refreshes that never arrived (battery saving stops the app) make the check less precise. */
    internal fun coverage(samples: Int, intervalMinutes: Int): Finding.Coverage {
        val expected = WINDOW_DAYS * 24 * 60 / intervalMinutes.coerceAtLeast(15)
        val share = (samples.toDouble() / expected).coerceAtMost(1.0)
        return Finding.Coverage(if (share < 0.6) Rating.Tip else Rating.Good, share, samples)
    }
}
