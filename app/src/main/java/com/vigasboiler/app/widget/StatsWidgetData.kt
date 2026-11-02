package com.vigasboiler.app.widget

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.vigasboiler.app.analysis.HeatingCheck
import com.vigasboiler.app.data.HistoryRow
import com.vigasboiler.app.feature.FeatureSnapshot
import com.vigasboiler.app.history.BurnerDaily
import com.vigasboiler.app.history.OutsideTemperature

/** Everything the statistics widget shows: the API's counters plus what the app's history adds. */
data class StatsWidgetData(
    val gasToday: Double?,
    val gas7Days: Double?,
    val gasMonth: Double?,
    val gasLastMonth: Double?,
    val gasYear: Double?,
    val heatToday: Double?,
    val heat7Days: Double?,
    val powerToday: Double?,
    val power7Days: Double?,
    val kwhPerCubicMeter: Double,
    /** Heat produced ÷ gas energy this year. */
    val efficiency: Double?,
    val burnerHoursToday: Double?,
    val burnerStartsToday: Double?,
    val avgHours7: Double?,
    val avgStarts7: Double?,
    /** Share of today's snapshots with the burner on. */
    val runningShareToday: Double?,
    val avgModulationToday: Double?,
    val outsideMinToday: Double?,
    val outsideMaxToday: Double?,
    val outsideAvg7: Double?,
    val gasPerDegreeDay: Double?,
    val totalHours: Double?,
    val totalStarts: Double?,
    val updatedAtMillis: Long,
) {
    /** Starts per burner hour over the last week (short cycling indicator). */
    val startsPerHour7: Double? get() = avgHours7?.takeIf { it > 0 }?.let { h -> avgStarts7?.div(h) }

    /** This month against the whole of last month, in percent; null without last month. */
    val monthChangePercent: Double? get() = gasLastMonth?.takeIf { it > 0 }?.let { last -> gasMonth?.let { (it - last) / last * 100 } }

    /** History rows the widget needs, keyed like [HeatingCheck.Series]. */
    class History(
        val hours: List<HistoryRow>,
        val starts: List<HistoryRow>,
        val burnerActive: List<HistoryRow>,
        val modulation: List<HistoryRow>,
        val outside: List<HistoryRow>,
        val gasToday: List<HistoryRow>,
    )

    companion object {
        private const val GAS = "heating.gas.consumption.summary.heating"
        private const val HEAT = "heating.heat.production.summary.heating"
        private const val POWER = "heating.power.consumption.summary.heating"

        fun from(snapshot: FeatureSnapshot, history: History, kwhPerCubicMeter: Double, zone: ZoneId, today: LocalDate): StatsWidgetData {
            fun n(feature: String, property: String) = snapshot[feature]?.number(property)
            val burner = BurnerDaily.stats(history.hours, history.starts, zone, today)
            val outside = OutsideTemperature.stats(history.outside, zone, today, days = 7)
            fun isToday(t: Long) = Instant.ofEpochMilli(t).atZone(zone).toLocalDate() == today
            val activeToday = history.burnerActive.filter { isToday(it.timestampMillis) }
            val onTimes = activeToday.filter { it.value == 1.0 }.map { it.timestampMillis }.toSet()
            val gasYear = n(GAS, "currentYear")
            val heatYear = n(HEAT, "currentYear")
            return StatsWidgetData(
                gasToday = n(GAS, "currentDay"),
                gas7Days = n(GAS, "lastSevenDays"),
                gasMonth = n(GAS, "currentMonth"),
                gasLastMonth = n(GAS, "lastMonth"),
                gasYear = gasYear,
                heatToday = n(HEAT, "currentDay"),
                heat7Days = n(HEAT, "lastSevenDays"),
                powerToday = n(POWER, "currentDay"),
                power7Days = n(POWER, "lastSevenDays"),
                kwhPerCubicMeter = kwhPerCubicMeter,
                efficiency = if (gasYear != null && heatYear != null && gasYear > 0) heatYear / (gasYear * kwhPerCubicMeter) else null,
                burnerHoursToday = burner.today?.hours,
                burnerStartsToday = burner.today?.starts,
                avgHours7 = burner.avgHours7,
                avgStarts7 = burner.avgStarts7,
                runningShareToday = activeToday.takeIf { it.size >= 2 }?.let { onTimes.size.toDouble() / it.size },
                avgModulationToday = history.modulation.filter { it.timestampMillis in onTimes && it.value > 0 }.map { it.value }.takeIf { it.isNotEmpty() }?.average(),
                outsideMinToday = outside.today?.min,
                outsideMaxToday = outside.today?.max,
                outsideAvg7 = outside.avg7,
                gasPerDegreeDay = HeatingCheck.gas(history.gasToday, history.outside, zone)?.perDegreeDay,
                totalHours = n("heating.burners.0.statistics", "hours"),
                totalStarts = n("heating.burners.0.statistics", "starts"),
                updatedAtMillis = snapshot.fetchedAtMillis,
            )
        }
    }
}
