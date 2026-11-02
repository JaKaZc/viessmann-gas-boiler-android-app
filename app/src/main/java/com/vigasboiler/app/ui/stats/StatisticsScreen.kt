package com.vigasboiler.app.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.stateIn
import com.vigasboiler.app.R
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import com.vigasboiler.app.ui.common.LineChart
import com.vigasboiler.app.history.OutsideTemperature
import com.vigasboiler.app.history.OutsideStats
import com.vigasboiler.app.history.BurnerDaily
import com.vigasboiler.app.history.BurnerDailyStats
import com.vigasboiler.app.feature.Feature
import com.vigasboiler.app.feature.FeatureSnapshot
import com.vigasboiler.app.history.ConsumptionHistory
import com.vigasboiler.app.history.HistoryRepository
import com.vigasboiler.app.history.PeriodValue
import com.vigasboiler.app.settings.AppSettings
import com.vigasboiler.app.ui.AppViewModel
import com.vigasboiler.app.ui.Navigate
import com.vigasboiler.app.ui.charts.ChartsContent
import com.vigasboiler.app.ui.common.AppCard
import com.vigasboiler.app.ui.common.appear
import androidx.compose.foundation.layout.Box
import com.vigasboiler.app.ui.common.ColumnChart
import com.vigasboiler.app.ui.common.SectionHeader
import com.vigasboiler.app.ui.common.StatusChip
import com.vigasboiler.app.ui.theme.AppColors
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import com.vigasboiler.app.ui.common.LabeledRow
import com.vigasboiler.app.ui.common.SectionCard
import com.vigasboiler.app.ui.common.ValueTile
import com.vigasboiler.app.ui.common.formatNumber
import com.vigasboiler.app.ui.common.formatWithUnit

private const val GAS = "heating.gas.consumption.summary.heating"
private const val POWER = "heating.power.consumption.summary.heating"
private const val HEAT = "heating.heat.production.summary.heating"

data class GasBars(val day: List<PeriodValue>, val week: List<PeriodValue>, val month: List<PeriodValue>, val year: List<PeriodValue>)

@HiltViewModel
class StatsViewModel @Inject constructor(history: HistoryRepository) : ViewModel() {
    private val since = System.currentTimeMillis() - 2L * 366 * 24 * 60 * 60_000
    private val zone = ZoneId.systemDefault()

    val bars: StateFlow<GasBars> = combine(
        history.series(GAS, "currentDay", since),
        history.series(GAS, "currentMonth", since),
        history.series(GAS, "currentYear", since),
    ) { day, month, year ->
        GasBars(
            day = ConsumptionHistory.daily(day, zone).takeLast(31),
            week = ConsumptionHistory.weekly(day, zone).takeLast(26),
            month = ConsumptionHistory.monthly(month, zone).takeLast(24),
            year = ConsumptionHistory.yearly(year, zone),
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GasBars(emptyList(), emptyList(), emptyList(), emptyList()))

    /** Outside temperature over the last 30 days from stored snapshots. */
    val outside: StateFlow<OutsideStats?> =
        history.series(OutsideTemperature.FEATURE, "value", System.currentTimeMillis() - 31L * 24 * 60 * 60_000)
            .map { rows -> OutsideTemperature.stats(rows, zone, LocalDate.now(zone)) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Burner hours and starts per day over the last 30 days (plus one day as baseline). */
    val burner: StateFlow<BurnerDailyStats?> = run {
        val since = LocalDate.now(zone).minusDays(31).atStartOfDay(zone).toInstant().toEpochMilli()
        combine(history.series(BurnerDaily.FEATURE, "hours", since), history.series(BurnerDaily.FEATURE, "starts", since)) { hours, starts ->
            BurnerDaily.stats(hours, starts, zone, LocalDate.now(zone))
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }
}

/** Charts first (the quick look most people want), the counters and comparisons under Totals. */
@Composable
fun StatisticsScreen(vm: AppViewModel, navigate: Navigate) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent, divider = {}) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.charts_tab)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.totals_tab)) })
        }
        if (tab == 0) ChartsContent(navigate) else StatsTotals(vm)
    }
}

@Composable
private fun StatsTotals(vm: AppViewModel, stats: StatsViewModel = hiltViewModel()) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val bars by stats.bars.collectAsStateWithLifecycle()
    val outside by stats.outside.collectAsStateWithLifecycle()
    val burner by stats.burner.collectAsStateWithLifecycle()
    val s = snapshot
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s == null) {
            item { Text(stringResource(R.string.dash_no_data), Modifier.padding(16.dp)) }
        } else {
            item { SectionHeader(stringResource(R.string.stats_burner)) }
            item { Box(Modifier.appear(1)) { BurnerTiles(s) } }
            item { Box(Modifier.appear(2)) { BurnerDailyCard(burner) } }
            item { SectionHeader(stringResource(R.string.stats_section_energy)) }
            s[GAS]?.let { item { Box(Modifier.appear(2)) { SummaryCard(stringResource(R.string.stats_gas), R.drawable.ic_nav_heating, it, settings.gasKwhPerCubicMeter) } } }
            s[HEAT]?.let { item { Box(Modifier.appear(3)) { SummaryCard(stringResource(R.string.stats_heat), R.drawable.ic_thermometer, it, null, efficiency(s, settings)) } } }
            s[POWER]?.let { item { Box(Modifier.appear(4)) { SummaryCard(stringResource(R.string.stats_power), R.drawable.ic_plug, it, null) } } }
        }
        item { SectionHeader(stringResource(R.string.stats_outside)) }
        item { Box(Modifier.appear(5)) { OutsideSection(outside) } }
        item { SectionHeader(stringResource(R.string.stats_history)) }
        item { Box(Modifier.appear(6)) { GasHistory(bars) } }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun BurnerTiles(s: FeatureSnapshot) {
    val stats = s["heating.burners.0.statistics"]
    val hours = stats?.number("hours")
    val starts = stats?.number("starts")
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ValueTile(stringResource(R.string.stats_hours), formatWithUnit(hours, "hour", 0), Modifier.weight(1f), icon = R.drawable.ic_clock)
        ValueTile(
            stringResource(R.string.stats_starts), formatNumber(starts ?: 0.0, 0), Modifier.weight(1f),
            sub = if (hours != null && starts != null && hours > 0) stringResource(R.string.stats_starts_per_hour_value, formatNumber(starts / hours, 2)) else null,
            icon = R.drawable.ic_nav_heating,
        )
    }
}

/** Burner hours and starts per day: today and yesterday, 7-day average and a 30-day bar chart. */
@Composable
private fun BurnerDailyCard(stats: BurnerDailyStats?) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val dateFormat = DateTimeFormatter.ofPattern("d.M.")
    fun hours(v: Double) = formatNumber(v, 1) + " h"
    SectionCard(stringResource(R.string.stats_burner_daily), icon = R.drawable.ic_calendar) {
        val days = stats?.days.orEmpty()
        if (days.isEmpty()) {
            Text(stringResource(R.string.stats_burner_daily_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(R.string.stats_today to stats?.today, R.string.stats_yesterday to stats?.yesterday).forEach { (label, day) ->
                ValueTile(
                    stringResource(label),
                    day?.let { hours(it.hours) } ?: "–",
                    Modifier.weight(1f),
                    sub = day?.let { pluralStringResource(R.plurals.stats_starts_count, it.starts.toInt(), formatNumber(it.starts, 0)) },
                    icon = R.drawable.ic_clock,
                )
            }
        }
        if (stats?.avgHours7 != null && stats.avgStarts7 != null) {
            LabeledRow(stringResource(R.string.stats_burner_avg7), stringResource(R.string.stats_burner_avg7_value, hours(stats.avgHours7), formatNumber(stats.avgStarts7, 1)))
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf(R.string.stats_hours, R.string.stats_starts).forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t)) }) }
        }
        ColumnChart(
            days.map { if (tab == 0) it.hours else it.starts },
            days.map { dateFormat.format(it.date) },
            Modifier.fillMaxWidth(),
            labelEvery = if (days.size > 10) days.size / 5 else 1,
        )
        Text(stringResource(R.string.stats_burner_daily_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Counter card: today's value prominent, then week, month and year with change chips. */
@Composable
private fun SummaryCard(title: String, icon: Int, f: Feature, kwhPerM3: Double?, footer: String? = null) {
    fun v(p: String) = f.number(p)
    fun fmt(p: String): String {
        val value = v(p) ?: return "–"
        val base = formatWithUnit(value, f.unit(p))
        return if (kwhPerM3 != null && f.unit(p) == "cubicMeter") "$base · ${formatWithUnit(value * kwhPerM3, "kilowattHour", 0)}" else base
    }
    SectionCard(title, icon = icon) {
        Column {
            Text(stringResource(R.string.stats_today), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fmt("currentDay"), style = MaterialTheme.typography.headlineMedium)
        }
        LabeledRow(stringResource(R.string.stats_7days), fmt("lastSevenDays"))
        PeriodRow(stringResource(R.string.stats_month), fmt("currentMonth"), v("currentMonth"), v("lastMonth"), stringResource(R.string.stats_last_month, fmt("lastMonth")))
        PeriodRow(stringResource(R.string.stats_year), fmt("currentYear"), v("currentYear"), v("lastYear"), stringResource(R.string.stats_last_year, fmt("lastYear")))
        if (kwhPerM3 != null) Text(stringResource(R.string.stats_gas_factor, formatNumber(kwhPerM3, 2)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (footer != null) Text(footer, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Current period with the previous one underneath and a coloured change chip (more = warning). */
@Composable
private fun PeriodRow(label: String, value: String, now: Double?, before: Double?, previousText: String) {
    val extra = AppColors.extra
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(previousText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (now != null && before != null && before > 0) {
                val pct = (now - before) / before * 100
                StatusChip((if (pct >= 0) "▲ +" else "▼ ") + formatNumber(pct, 0) + " %", null, if (pct > 0) extra.warning else extra.success)
            }
        }
    }
}

@Composable
private fun efficiency(s: FeatureSnapshot, settings: AppSettings): String? {
    val gas = s[GAS]?.number("currentYear") ?: return null
    val heat = s[HEAT]?.number("currentYear") ?: return null
    if (gas <= 0) return null
    return stringResource(R.string.stats_efficiency, formatNumber(heat / (gas * settings.gasKwhPerCubicMeter) * 100, 0) + " %")
}

@Composable
private fun GasHistory(bars: GasBars) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf(R.string.tab_day, R.string.tab_week, R.string.tab_month, R.string.tab_year)
    val data = when (tab) {
        0 -> bars.day
        1 -> bars.week
        2 -> bars.month
        else -> bars.year
    }
    val shortDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    val labels = data.map {
        when (tab) {
            0 -> shortDate.format(it.start)
            1 -> "W" + ConsumptionHistory.weekOf(it.start)
            2 -> "${it.start.monthValue}/${it.start.year % 100}"
            else -> it.start.year.toString()
        }
    }
    AppCard {
        PrimaryTabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(t)) }) }
        }
        if (data.isEmpty()) {
            Text(stringResource(R.string.stats_history_empty), style = MaterialTheme.typography.bodySmall)
        } else {
            ColumnChart(data.map { it.value }, labels, Modifier.fillMaxWidth(), labelEvery = if (data.size > 12) data.size / 6 else 1)
        }
    }
}

/** Outside temperature: today's range, averages, extremes and a 30-day min/avg/max chart. */
@Composable
private fun OutsideSection(stats: OutsideStats?) {
    val cold = AppColors.extra.cold
    val dateFormat = DateTimeFormatter.ofPattern("d.M.")
    fun deg(v: Double?) = v?.let { formatNumber(it) + " °C" } ?: "–"
    AppCard {
        if (stats == null || stats.days.isEmpty()) {
            Text(stringResource(R.string.stats_outside_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@AppCard
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ValueTile(
                stringResource(R.string.stats_outside_today),
                stats.today?.let { "${formatNumber(it.min)}…${formatNumber(it.max)}°" } ?: "–",
                Modifier.weight(1f),
                icon = R.drawable.ic_thermometer,
                accent = cold,
            )
            ValueTile(
                stringResource(R.string.stats_outside_avg7),
                deg(stats.avg7),
                Modifier.weight(1f),
                sub = stringResource(R.string.stats_outside_avg30, deg(stats.avg30)),
                icon = R.drawable.ic_calendar,
                accent = cold,
            )
        }
        stats.coldest?.let { LabeledRow(stringResource(R.string.stats_outside_coldest), "${deg(it.min)} · ${dateFormat.format(it.date)}") }
        stats.warmest?.let { LabeledRow(stringResource(R.string.stats_outside_warmest), "${deg(it.max)} · ${dateFormat.format(it.date)}") }
        val days = stats.days
        if (days.size >= 2) {
            // x = day index; three series: max, average, min.
            LineChart(
                listOf(
                    days.mapIndexed { i, d -> i to d.max },
                    days.mapIndexed { i, d -> i to d.avg },
                    days.mapIndexed { i, d -> i to d.min },
                ),
                xLabel = { x -> days.getOrNull(x.toInt())?.let { dateFormat.format(it.date) } ?: "" },
                labelEvery = if (days.size > 10) days.size / 5 else 1,
            )
            Text(stringResource(R.string.stats_outside_legend), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(R.string.stats_outside_growing, days.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
