package com.vigasboiler.app.history

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.vigasboiler.app.data.HistoryRow
import com.vigasboiler.app.data.SeriesEntity
import com.vigasboiler.app.data.SnapshotDao
import com.vigasboiler.app.data.SnapshotValueEntity
import com.vigasboiler.app.feature.FeatureSnapshot
import com.vigasboiler.app.feature.PropertyValue
import com.vigasboiler.app.feature.SnapshotListener
import com.vigasboiler.app.settings.SettingsRepository

data class HistoryValue(val feature: String, val property: String, val value: Double, val unit: String?)

object HistoryValues {
    /**
     * Every number of every enabled feature; booleans become 0/1 so states can be charted.
     * The house coordinates (`*.houseLocation`) are personal and static, so they are not stored.
     */
    fun extract(snapshot: FeatureSnapshot): List<HistoryValue> = snapshot.device.filter { it.isEnabled && !it.name.endsWith("houseLocation") }.flatMap { f ->
        f.properties.mapNotNull { (name, v) ->
            when (v) {
                is PropertyValue.Number -> HistoryValue(f.name, name, v.value, v.unit)
                is PropertyValue.Bool -> HistoryValue(f.name, name, if (v.value) 1.0 else 0.0, null)
                else -> null
            }
        }
    }
}

/** One bar: the period start and the consumption in it. */
data class PeriodValue(val start: LocalDate, val value: Double)

/**
 * Rebuilds consumption per period from snapshots of the API's running counters
 * (`currentDay`, `currentMonth`, `currentYear`). A counter's highest value within its own
 * period is that period's total; periods without snapshots are simply missing.
 */
object ConsumptionHistory {
    fun daily(currentDay: List<HistoryRow>, zone: ZoneId): List<PeriodValue> =
        maxPerPeriod(currentDay, zone) { it }

    /** Sum of daily totals per ISO week (Monday start). */
    fun weekly(currentDay: List<HistoryRow>, zone: ZoneId): List<PeriodValue> =
        daily(currentDay, zone)
            .groupBy { it.start.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)) }
            .map { (week, days) -> PeriodValue(week, days.sumOf { it.value }) }
            .sortedBy { it.start }

    fun monthly(currentMonth: List<HistoryRow>, zone: ZoneId): List<PeriodValue> =
        maxPerPeriod(currentMonth, zone) { YearMonth.from(it).atDay(1) }

    fun yearly(currentYear: List<HistoryRow>, zone: ZoneId): List<PeriodValue> =
        maxPerPeriod(currentYear, zone) { it.withDayOfYear(1) }

    private fun maxPerPeriod(rows: List<HistoryRow>, zone: ZoneId, periodStart: (LocalDate) -> LocalDate) =
        rows.groupBy { periodStart(Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate()) }
            .map { (start, values) -> PeriodValue(start, values.maxOf { it.value }) }
            .sortedBy { it.start }

    /** ISO week number, for labels. */
    fun weekOf(date: LocalDate): Int = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
}

/**
 * Averages rows into fixed buckets so charts get few, integer x values (Vico rejects x values
 * with more than four decimals, and a 30-day chart needs no 30-minute resolution).
 */
object HistoryBuckets {
    /** (bucket index since [startMillis], average value) for every non-empty bucket. */
    fun bucket(rows: List<HistoryRow>, startMillis: Long, bucketMillis: Long): List<Pair<Int, Double>> =
        rows.filter { it.timestampMillis >= startMillis }
            .groupBy { ((it.timestampMillis - startMillis) / bucketMillis).toInt() }
            .map { (index, values) -> index to values.map { it.value }.average() }
            .sortedBy { it.first }
}

/** Minimum, average and maximum of one local day. */
data class DayRange(val date: LocalDate, val min: Double, val avg: Double, val max: Double)

/** Outside temperature statistics from stored snapshots (`heating.sensors.temperature.outside`). */
data class OutsideStats(
    val days: List<DayRange>,
    val today: DayRange?,
    val avg7: Double?,
    val avg30: Double?,
    val coldest: DayRange?,
    val warmest: DayRange?,
)

object OutsideTemperature {
    const val FEATURE = "heating.sensors.temperature.outside"

    fun daily(rows: List<HistoryRow>, zone: ZoneId): List<DayRange> =
        rows.groupBy { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() }
            .map { (date, values) ->
                val v = values.map { it.value }
                DayRange(date, v.min(), v.average(), v.max())
            }
            .sortedBy { it.date }

    /** Stats over the last [days] days ending on [today]; averages are means of daily averages. */
    fun stats(rows: List<HistoryRow>, zone: ZoneId, today: LocalDate, days: Int = 30): OutsideStats {
        val daily = daily(rows, zone).filter { !it.date.isBefore(today.minusDays(days - 1L)) && !it.date.isAfter(today) }
        fun avgOver(n: Long) = daily.filter { !it.date.isBefore(today.minusDays(n - 1)) }.map { it.avg }.takeIf { it.isNotEmpty() }?.average()
        return OutsideStats(
            days = daily,
            today = daily.lastOrNull { it.date == today },
            avg7 = avgOver(7),
            avg30 = avgOver(days.toLong()),
            coldest = daily.minByOrNull { it.min },
            warmest = daily.maxByOrNull { it.max },
        )
    }
}

/** Burner operating hours and starts within one local day. */
data class BurnerDay(val date: LocalDate, val hours: Double, val starts: Double)

data class BurnerDailyStats(
    val days: List<BurnerDay>,
    val today: BurnerDay?,
    val yesterday: BurnerDay?,
    val avgHours7: Double?,
    val avgStarts7: Double?,
)

/**
 * Daily burner hours and starts from snapshots of the lifetime counters in
 * `heating.burners.0.statistics`. A day's value is its last reading minus the last reading of
 * the day before; a day without a reading on the previous day has no baseline and is left out
 * (it would show only part of the day). The boiler counts whole hours, so daily hours are too.
 */
object BurnerDaily {
    const val FEATURE = "heating.burners.0.statistics"

    /** Counter increase per local day, for days whose previous day also has a reading. */
    fun perDay(rows: List<HistoryRow>, zone: ZoneId): Map<LocalDate, Double> {
        val lastOfDay = rows.sortedBy { it.timestampMillis }
            .groupBy { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() }
            .mapValues { (_, values) -> values.last().value }
        return lastOfDay.mapNotNull { (date, end) ->
            val start = lastOfDay[date.minusDays(1)] ?: return@mapNotNull null
            // A replaced control unit can restart the counter; that day has no usable value.
            (end - start).takeIf { it >= 0 }?.let { date to it }
        }.toMap()
    }

    fun stats(hours: List<HistoryRow>, starts: List<HistoryRow>, zone: ZoneId, today: LocalDate, days: Int = 30): BurnerDailyStats {
        val h = perDay(hours, zone)
        val s = perDay(starts, zone)
        val all = h.keys.intersect(s.keys)
            .filter { !it.isBefore(today.minusDays(days - 1L)) && !it.isAfter(today) }
            .sorted()
            .map { BurnerDay(it, h.getValue(it), s.getValue(it)) }
        // Averages over complete days only: today is still running.
        val week = all.filter { it.date.isBefore(today) && !it.date.isBefore(today.minusDays(7)) }
        return BurnerDailyStats(
            days = all,
            today = all.lastOrNull { it.date == today },
            yesterday = all.lastOrNull { it.date == today.minusDays(1) },
            avgHours7 = week.takeIf { it.isNotEmpty() }?.map { it.hours }?.average(),
            avgStarts7 = week.takeIf { it.isNotEmpty() }?.map { it.starts }?.average(),
        )
    }
}

object HistoryCsv {
    const val HEADER = "timestamp,feature,property,value,unit"

    fun line(row: HistoryRow): String =
        listOf(Instant.ofEpochMilli(row.timestampMillis).toString(), row.feature, row.property, row.value.toString(), row.unit.orEmpty())
            .joinToString(",") { escape(it) }

    private fun escape(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

/** Stores snapshots (as a [SnapshotListener]) and serves history queries. */
class HistoryRepository(
    private val dao: SnapshotDao,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : SnapshotListener, HistoryStore {

    // A restore can run while a refresh stores its snapshot.
    private val writeLock = Mutex()
    private val seriesIds = HashMap<Pair<String, String>, Long>()

    override suspend fun onSnapshot(previous: FeatureSnapshot?, current: FeatureSnapshot) = record(current)

    suspend fun record(snapshot: FeatureSnapshot) {
        val values = HistoryValues.extract(snapshot)
        if (values.isEmpty()) return
        insert(values.map { TimedValue(snapshot.fetchedAtMillis, it) })
        dao.deleteBefore(clock() - settings.current().retentionDays * DAY)
    }

    /** Stores values with their own timestamps (demo mode's generated week). */
    override suspend fun insert(values: List<TimedValue>) = writeLock.withLock {
        if (seriesIds.isEmpty()) dao.allSeries().forEach { seriesIds[it.feature to it.property] = it.id }
        val rows = values.map { (t, v) ->
            val key = v.feature to v.property
            val id = seriesIds[key] ?: run {
                dao.insertSeries(SeriesEntity(feature = v.feature, property = v.property, unit = v.unit))
                // IGNORE returns -1 on conflict, so re-read the id.
                dao.allSeries().first { it.feature == v.feature && it.property == v.property }.id
            }.also { seriesIds[key] = it }
            SnapshotValueEntity(id, t, v.value)
        }
        dao.insertValues(rows)
    }

    override suspend fun rows(fromMillis: Long, untilMillis: Long): List<HistoryRow> = dao.between(fromMillis, untilMillis)

    override suspend fun oldestMillis(): Long? = dao.oldest()

    /** Deletes the whole history. */
    suspend fun clear() = writeLock.withLock {
        dao.deleteAllValues()
        dao.deleteAllSeries()
        seriesIds.clear()
    }

    fun series(feature: String, property: String, sinceMillis: Long): Flow<List<HistoryRow>> =
        dao.series(feature, property, sinceMillis)

    /** Writes the whole retained history as CSV and returns the file. */
    suspend fun exportCsv(dir: File): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val file = File(dir, "viessmann-history.csv")
        file.bufferedWriter().use { out ->
            out.appendLine(HistoryCsv.HEADER)
            dao.allSince(0).forEach { out.appendLine(HistoryCsv.line(it)) }
        }
        file
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
