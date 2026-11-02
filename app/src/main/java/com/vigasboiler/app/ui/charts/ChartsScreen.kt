package com.vigasboiler.app.ui.charts

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import com.vigasboiler.app.R
import com.vigasboiler.app.history.ChartBuilder
import com.vigasboiler.app.history.ChartData
import com.vigasboiler.app.history.ChartKind
import com.vigasboiler.app.history.ChartRange
import com.vigasboiler.app.history.ChartSet
import com.vigasboiler.app.history.HistoryRepository
import com.vigasboiler.app.history.SeriesRole
import com.vigasboiler.app.ui.Navigate
import com.vigasboiler.app.ui.common.AppCard
import com.vigasboiler.app.ui.common.BarChart
import com.vigasboiler.app.ui.common.IconBadge
import com.vigasboiler.app.ui.common.LabeledRow
import com.vigasboiler.app.ui.common.LineSpec
import com.vigasboiler.app.ui.common.TimeLineChart
import com.vigasboiler.app.ui.common.formatNumber
import com.vigasboiler.app.ui.theme.AppColors

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChartsViewModel @Inject constructor(history: HistoryRepository) : ViewModel() {
    val range = MutableStateFlow(ChartRange.Day)

    val charts: StateFlow<ChartSet?> = range.flatMapLatest { r ->
        // One extra day: counters need the reading before the range for its first bucket.
        val since = System.currentTimeMillis() - (r.hours + 24) * 3_600_000L
        val sources = ChartBuilder.Source.entries
        combine(sources.map { history.series(it.feature, it.property, since) }) { rows ->
            ChartBuilder.build(r, System.currentTimeMillis(), sources.zip(rows.toList()).toMap(), ZoneId.systemDefault())
        }.flowOn(Dispatchers.Default)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Charts page of the Statistics tab: range selector and one card per chart; a card opens [ChartDetailScreen]. */
@Composable
fun ChartsContent(navigate: Navigate, vm: ChartsViewModel = hiltViewModel()) {
    val range by vm.range.collectAsStateWithLifecycle()
    val set by vm.charts.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { RangeSelector(range) { vm.range.value = it } }
        val charts = set?.charts?.filterNot { it.isEmpty }.orEmpty()
        if (set != null && charts.isEmpty()) item { Text(stringResource(R.string.charts_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val shown = set
        if (shown != null) {
            items(charts, key = { it.kind.name }) { chart ->
                AppCard(onClick = { navigate("chart:${chart.kind.name}|${shown.range.name}") }) {
                    ChartHeader(chart, shown.range)
                    ChartBody(chart, shown.range, height = 180)
                    Legend(chart, shown.range)
                }
            }
        }
    }
}

/** One chart full size, with its own range selector, figures and how to read it. */
@Composable
fun ChartDetailScreen(kind: ChartKind, initialRange: ChartRange, vm: ChartsViewModel = hiltViewModel()) {
    val range by vm.range.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.range.value = initialRange }
    val set by vm.charts.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RangeSelector(range) { vm.range.value = it }
        val shown = set ?: return@Column
        val chart = shown[kind]?.takeUnless { it.isEmpty }
        if (chart == null) {
            Text(stringResource(R.string.charts_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        AppCard {
            ChartHeader(chart, shown.range)
            ChartBody(chart, shown.range, height = 320)
            Legend(chart, shown.range)
        }
        chart.stats?.let { st ->
            val r = shown.range
            AppCard {
                if (kind.columns) {
                    st.total?.let { LabeledRow(stringResource(R.string.chart_total), formatValue(kind, it, r)) }
                } else {
                    st.now?.let { LabeledRow(stringResource(R.string.chart_now), formatValue(kind, it, r)) }
                }
                LabeledRow(stringResource(R.string.chart_min), formatValue(kind, st.min, r))
                LabeledRow(stringResource(R.string.chart_max), formatValue(kind, st.max, r))
                LabeledRow(stringResource(R.string.chart_avg), formatValue(kind, st.average, r))
            }
        }
        Text(stringResource(about(kind)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RangeSelector(range: ChartRange, onSelect: (ChartRange) -> Unit) {
    val labels = mapOf(ChartRange.Day to R.string.hist_24h, ChartRange.Week to R.string.hist_7d, ChartRange.Month to R.string.hist_30d)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ChartRange.entries.forEachIndexed { i, r ->
            SegmentedButton(selected = r == range, onClick = { onSelect(r) }, shape = SegmentedButtonDefaults.itemShape(i, ChartRange.entries.size)) {
                Text(stringResource(labels.getValue(r)))
            }
        }
    }
}

@Composable
private fun ChartHeader(chart: ChartData, range: ChartRange) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(icon(chart.kind), color(chart.series.firstOrNull()?.role ?: SeriesRole.Room), size = 36)
        Column(Modifier.weight(1f)) {
            Text(stringResource(title(chart.kind)), style = MaterialTheme.typography.titleMedium)
            chart.stats?.let { st ->
                fun v(x: Double) = formatValue(chart.kind, x, range)
                Text(
                    when {
                        chart.kind.columns -> stringResource(R.string.chart_stats_total, st.total?.let(::v) ?: "–", v(st.max), v(st.average))
                        chart.kind == ChartKind.Modulation -> stringResource(R.string.chart_stats_modulation, st.now?.let(::v) ?: "–", v(st.max), v(st.average))
                        else -> stringResource(R.string.chart_stats_line, st.now?.let(::v) ?: "–", v(st.min), v(st.max), v(st.average))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChartBody(chart: ChartData, range: ChartRange, height: Int) {
    val zone = ZoneId.systemDefault()
    val hourly = chart.bucketMillis < 24 * 3_600_000L && range == ChartRange.Day
    val pattern = DateTimeFormatter.ofPattern(if (hourly) "HH:mm" else "d.M.")
    val label = { x: Double -> pattern.format(Instant.ofEpochMilli(chart.startMillis + (x * chart.bucketMillis).toLong()).atZone(zone)) }
    val decimals = decimalsOf(chart.kind)
    if (chart.kind.columns) {
        val series = chart.series.first()
        val labelEvery = when (range) { ChartRange.Day -> 4; ChartRange.Week -> 1; ChartRange.Month -> 5 }
        BarChart(series.points, color(series.role), label, labelEvery = labelEvery, height = height.dp, decimals = decimals)
    } else {
        val labelEvery = when (range) { ChartRange.Day -> 8; ChartRange.Week -> 12; ChartRange.Month -> 20 }
        TimeLineChart(chart.series.map { lineSpec(it.role, it.points) }, label, labelEvery = labelEvery, height = height.dp, decimals = decimals)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(chart: ChartData, range: ChartRange) {
    if (chart.series.size < 2 && !chart.kind.columns) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        chart.series.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color(s.role)))
                Text(stringResource(seriesLabel(s.role, range)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun lineSpec(role: SeriesRole, points: List<Pair<Int, Double>>) = LineSpec(
    points,
    color(role),
    dashed = role == SeriesRole.Target || role == SeriesRole.Setpoint,
    area = role == SeriesRole.Room || role == SeriesRole.Supply || role == SeriesRole.Modulation || role == SeriesRole.Pressure || role == SeriesRole.Outside,
)

@Composable
private fun color(role: SeriesRole): Color {
    val extra = AppColors.extra
    val scheme = MaterialTheme.colorScheme
    return when (role) {
        SeriesRole.Room -> scheme.primary
        SeriesRole.Target -> scheme.onSurfaceVariant
        SeriesRole.Outside -> extra.cold
        SeriesRole.Supply, SeriesRole.Modulation, SeriesRole.Gas -> extra.hot
        SeriesRole.Setpoint -> extra.hot.copy(alpha = 0.7f)
        SeriesRole.CircuitSupply -> scheme.tertiary
        SeriesRole.Pressure, SeriesRole.BurnerTime -> scheme.primary
    }
}

@StringRes
private fun seriesLabel(role: SeriesRole, range: ChartRange) = when (role) {
    SeriesRole.Room -> R.string.series_room
    SeriesRole.Target -> R.string.series_target
    SeriesRole.Outside -> R.string.series_outside
    SeriesRole.Supply -> R.string.series_supply
    SeriesRole.CircuitSupply -> R.string.series_circuit
    SeriesRole.Setpoint -> R.string.series_setpoint
    SeriesRole.Modulation -> R.string.series_modulation
    SeriesRole.Pressure -> R.string.series_pressure
    SeriesRole.Gas -> if (range == ChartRange.Day) R.string.series_gas_hour else R.string.series_gas_day
    SeriesRole.BurnerTime -> if (range == ChartRange.Day) R.string.series_burner_hour else R.string.series_burner_day
}

@StringRes
internal fun title(kind: ChartKind) = when (kind) {
    ChartKind.Temperatures -> R.string.chart_temperatures
    ChartKind.Supply -> R.string.chart_supply
    ChartKind.Modulation -> R.string.chart_modulation
    ChartKind.Pressure -> R.string.chart_pressure
    ChartKind.Gas -> R.string.chart_gas
    ChartKind.Burner -> R.string.chart_burner
}

@StringRes
private fun about(kind: ChartKind) = when (kind) {
    ChartKind.Temperatures -> R.string.chart_about_temperatures
    ChartKind.Supply -> R.string.chart_about_supply
    ChartKind.Modulation -> R.string.chart_about_modulation
    ChartKind.Pressure -> R.string.chart_about_pressure
    ChartKind.Gas -> R.string.chart_about_gas
    ChartKind.Burner -> R.string.chart_about_burner
}

@DrawableRes
private fun icon(kind: ChartKind) = when (kind) {
    ChartKind.Temperatures -> R.drawable.ic_thermometer
    ChartKind.Supply -> R.drawable.ic_drop
    ChartKind.Modulation -> R.drawable.ic_nav_heating
    ChartKind.Pressure -> R.drawable.ic_gauge
    ChartKind.Gas -> R.drawable.ic_nav_stats
    ChartKind.Burner -> R.drawable.ic_clock
}

private fun unitOf(kind: ChartKind) = when (kind) {
    ChartKind.Temperatures -> "°"
    ChartKind.Supply -> " °C"
    ChartKind.Modulation -> " %"
    ChartKind.Pressure -> " bar"
    ChartKind.Gas -> " m³"
    ChartKind.Burner -> " h"
}

private fun decimalsOf(kind: ChartKind) = when (kind) {
    ChartKind.Pressure, ChartKind.Gas -> 2
    ChartKind.Modulation, ChartKind.Burner -> 0
    else -> 1
}

/** Burner time is minutes per hour for 24 h and hours per day for longer ranges. */
private fun formatValue(kind: ChartKind, value: Double, range: ChartRange): String = when {
    kind == ChartKind.Burner && range == ChartRange.Day -> formatNumber(value, 0) + " min"
    kind == ChartKind.Burner -> formatNumber(value, 1) + " h"
    else -> formatNumber(value, decimalsOf(kind)) + unitOf(kind)
}
