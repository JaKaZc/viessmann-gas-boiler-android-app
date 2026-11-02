package com.vigasboiler.app.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import com.vigasboiler.app.data.HistoryRow

/** Time range of the charts: line buckets (averages) and column buckets (totals per hour or day). */
enum class ChartRange(val hours: Int, val bucketMinutes: Int, val daily: Boolean) {
    Day(24, 30, daily = false),
    Week(24 * 7, 120, daily = true),
    Month(24 * 30, 360, daily = true),
}

enum class ChartKind(val columns: Boolean) {
    Temperatures(false),
    Supply(false),
    Modulation(false),
    Pressure(false),
    Gas(true),
    Burner(true),
}

/** What a series is; the UI picks colour, line style and label from it. */
enum class SeriesRole { Room, Target, Outside, Supply, CircuitSupply, Setpoint, Modulation, Pressure, Gas, BurnerTime }

data class ChartSeries(val role: SeriesRole, val points: List<Pair<Int, Double>>)

/** Figures under a chart's title: current value, range and average (lines) or total (columns). */
data class ChartStats(val now: Double?, val min: Double, val max: Double, val average: Double, val total: Double? = null)

/**
 * One chart: x is a bucket index from [startMillis] in steps of [bucketMillis] (hours or local
 * days for columns), so labels can be formatted back to times.
 */
data class ChartData(
    val kind: ChartKind,
    val series: List<ChartSeries>,
    val stats: ChartStats?,
    val startMillis: Long,
    val bucketMillis: Long,
) {
    val isEmpty: Boolean get() = series.all { it.points.isEmpty() }
}

data class ChartSet(val range: ChartRange, val charts: List<ChartData>) {
    operator fun get(kind: ChartKind): ChartData? = charts.firstOrNull { it.kind == kind }
}

/** Builds every chart of the Charts screen from stored history. Pure, so it is unit-tested. */
object ChartBuilder {
    /** History series the charts read. */
    enum class Source(val feature: String, val property: String) {
        Room("heating.circuits.0.sensors.temperature.room", "value"),
        Outside("heating.sensors.temperature.outside", "value"),
        Supply("heating.boiler.sensors.temperature.commonSupply", "value"),
        CircuitSupply("heating.circuits.0.sensors.temperature.supply", "value"),
        Setpoint("heating.circuits.0.temperature", "value"),
        Modulation("heating.burners.0.modulation", "value"),
        BurnerActive("heating.burners.0", "active"),
        BurnerHours("heating.burners.0.statistics", "hours"),
        Pressure("heating.sensors.pressure.supply", "value"),
        GasToday("heating.gas.consumption.summary.heating", "currentDay"),
        NormalActive("heating.circuits.0.operating.programs.normal", "active"),
        NormalTemp("heating.circuits.0.operating.programs.normal", "temperature"),
        ComfortActive("heating.circuits.0.operating.programs.comfort", "active"),
        ComfortTemp("heating.circuits.0.operating.programs.comfort", "temperature"),
        ReducedActive("heating.circuits.0.operating.programs.reduced", "active"),
        ReducedTemp("heating.circuits.0.operating.programs.reduced", "temperature"),
    }

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    fun build(range: ChartRange, nowMillis: Long, data: Map<Source, List<HistoryRow>>, zone: ZoneId): ChartSet {
        val start = nowMillis - range.hours * HOUR
        val bucket = range.bucketMinutes * 60_000L
        fun rows(s: Source) = data[s].orEmpty().filter { it.timestampMillis in start..nowMillis }.sortedBy { it.timestampMillis }
        fun all(s: Source) = data[s].orEmpty().filter { it.timestampMillis <= nowMillis }.sortedBy { it.timestampMillis }
        fun line(role: SeriesRole, rows: List<HistoryRow>) = ChartSeries(role, HistoryBuckets.bucket(rows, start, bucket))
        fun lineChart(kind: ChartKind, primary: List<HistoryRow>, vararg series: ChartSeries, statsOf: List<HistoryRow> = primary) =
            ChartData(kind, series.filter { it.points.isNotEmpty() }, stats(statsOf), start, bucket)

        val room = rows(Source.Room)
        val outside = rows(Source.Outside)
        val target = target(data, start, nowMillis)
        val temperatures = if (room.isNotEmpty()) {
            lineChart(ChartKind.Temperatures, room, line(SeriesRole.Room, room), line(SeriesRole.Target, target), line(SeriesRole.Outside, outside))
        } else {
            lineChart(ChartKind.Temperatures, outside, line(SeriesRole.Outside, outside))
        }
        val supply = rows(Source.Supply)
        val modulation = rows(Source.Modulation)
        val pressure = rows(Source.Pressure)
        return ChartSet(
            range,
            listOf(
                temperatures,
                lineChart(ChartKind.Supply, supply, line(SeriesRole.Supply, supply), line(SeriesRole.Setpoint, rows(Source.Setpoint)), line(SeriesRole.CircuitSupply, rows(Source.CircuitSupply))),
                // Average and minimum only while the burner runs: 0 % when off says nothing.
                lineChart(ChartKind.Modulation, modulation, line(SeriesRole.Modulation, modulation), statsOf = modulation.filter { it.value > 0 }),
                lineChart(ChartKind.Pressure, pressure, line(SeriesRole.Pressure, pressure)),
                // Counters keep the reading before the range: its increase belongs to the first bucket.
                gas(range, all(Source.GasToday), start, zone),
                burner(range, rows(Source.BurnerActive), all(Source.BurnerHours), start, zone),
            ),
        )
    }

    private fun stats(rows: List<HistoryRow>): ChartStats? {
        if (rows.isEmpty()) return null
        val values = rows.map { it.value }
        return ChartStats(rows.last().value, values.min(), values.max(), values.average())
    }

    /** Target room temperature at each snapshot: the temperature of the program that was active. */
    internal fun target(data: Map<Source, List<HistoryRow>>, start: Long, end: Long): List<HistoryRow> {
        fun map(s: Source) = data[s].orEmpty().associate { it.timestampMillis to it.value }
        val normalOn = map(Source.NormalActive); val normalT = map(Source.NormalTemp)
        val comfortOn = map(Source.ComfortActive); val comfortT = map(Source.ComfortTemp)
        val reducedOn = map(Source.ReducedActive); val reducedT = map(Source.ReducedTemp)
        return (normalOn.keys + comfortOn.keys + reducedOn.keys).filter { it in start..end }.sorted().mapNotNull { t ->
            val value = when {
                comfortOn[t] == 1.0 -> comfortT[t]
                normalOn[t] == 1.0 -> normalT[t]
                reducedOn[t] == 1.0 -> reducedT[t]
                else -> null
            } ?: return@mapNotNull null
            HistoryRow(t, "target", "value", value, "celsius")
        }
    }

    /** Column buckets: whole hours (24 h) or local days (7 / 30 days). */
    private class Columns(range: ChartRange, start: Long, val zone: ZoneId) {
        val daily = range.daily
        val firstDay: LocalDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val startMillis: Long = if (daily) firstDay.atStartOfDay(zone).toInstant().toEpochMilli() else start - start % HOUR
        val bucketMillis = if (daily) DAY else HOUR
        fun index(t: Long): Int =
            if (daily) ChronoUnit.DAYS.between(firstDay, Instant.ofEpochMilli(t).atZone(zone).toLocalDate()).toInt()
            else ((t - startMillis) / HOUR).toInt()
    }

    /**
     * Gas per hour or day from the "today" counter: each increase belongs to the bucket of the
     * later reading; after midnight the counter restarts, so its new value is the increase.
     */
    internal fun gas(range: ChartRange, gasToday: List<HistoryRow>, start: Long, zone: ZoneId): ChartData {
        val columns = Columns(range, start, zone)
        val perBucket = sortedMapOf<Int, Double>()
        gasToday.zipWithNext { a, b ->
            val increase = if (b.value >= a.value) b.value - a.value else b.value
            perBucket.merge(columns.index(b.timestampMillis), increase, Double::plus)
        }
        return columnChart(ChartKind.Gas, SeriesRole.Gas, perBucket, columns)
    }

    /**
     * Burner running time: minutes per hour from the on/off snapshots (24 h), or hours per day
     * from the lifetime hours counter (7 / 30 days, whole hours as the boiler counts them).
     */
    internal fun burner(range: ChartRange, active: List<HistoryRow>, hours: List<HistoryRow>, start: Long, zone: ZoneId): ChartData {
        val columns = Columns(range, start, zone)
        val perBucket = sortedMapOf<Int, Double>()
        if (columns.daily) {
            hours.zipWithNext { a, b -> perBucket.merge(columns.index(b.timestampMillis), (b.value - a.value).coerceAtLeast(0.0), Double::plus) }
        } else {
            active.groupBy { columns.index(it.timestampMillis) }.forEach { (i, rows) -> perBucket[i] = rows.count { it.value == 1.0 } * 60.0 / rows.size }
        }
        return columnChart(ChartKind.Burner, SeriesRole.BurnerTime, perBucket, columns)
    }

    private fun columnChart(kind: ChartKind, role: SeriesRole, perBucket: Map<Int, Double>, columns: Columns): ChartData {
        val points = perBucket.filterKeys { it >= 0 }.map { (i, v) -> i to v }
        val values = points.map { it.second }
        val stats = values.takeIf { it.isNotEmpty() }?.let { ChartStats(it.last(), it.min(), it.max(), it.average(), total = it.sum()) }
        return ChartData(kind, listOf(ChartSeries(role, points)).filter { it.points.isNotEmpty() }, stats, columns.startMillis, columns.bucketMillis)
    }
}
