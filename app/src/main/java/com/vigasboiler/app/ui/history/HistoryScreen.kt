package com.vigasboiler.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import com.vigasboiler.app.R
import com.vigasboiler.app.data.HistoryRow
import com.vigasboiler.app.history.HistoryBuckets
import com.vigasboiler.app.history.HistoryRepository
import com.vigasboiler.app.ui.common.LineChart
import com.vigasboiler.app.ui.common.SectionCard

/** Range, bucket size and label spacing (in buckets) per tab. */
enum class HistoryRange(val hours: Int, val label: Int, val bucketMinutes: Int, val labelEvery: Int) {
    Day(24, R.string.hist_24h, 30, 8), // label every 4 h
    Week(24 * 7, R.string.hist_7d, 120, 12), // label every day
    Month(24 * 30, R.string.hist_30d, 360, 20), // label every 5 days
}

/** Chart-ready points (bucket index → average), computed off the UI thread. */
data class HistoryCharts(
    val range: HistoryRange,
    val startMillis: Long,
    val temperatures: List<List<Pair<Int, Double>>>,
    val modulation: List<Pair<Int, Double>>,
    val burner: List<Pair<Int, Double>>,
) {
    val isEmpty: Boolean get() = temperatures.all { it.isEmpty() } && modulation.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(history: HistoryRepository) : ViewModel() {
    val range = MutableStateFlow(HistoryRange.Day)

    val charts: StateFlow<HistoryCharts?> = range.flatMapLatest { r ->
        val since = System.currentTimeMillis() - r.hours * 3_600_000L
        combine(
            history.series("heating.sensors.temperature.outside", "value", since),
            history.series("heating.boiler.sensors.temperature.commonSupply", "value", since),
            history.series("heating.circuits.0.sensors.temperature.supply", "value", since),
            history.series("heating.burners.0.modulation", "value", since),
            history.series("heating.burners.0", "active", since),
        ) { outside, boiler, circuit, modulation, burner ->
            val bucket = r.bucketMinutes * 60_000L
            fun points(rows: List<HistoryRow>) = HistoryBuckets.bucket(rows, since, bucket)
            HistoryCharts(r, since, listOf(points(outside), points(boiler), points(circuit)), points(modulation), points(burner))
        }.flowOn(Dispatchers.Default)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun HistoryScreen(vm: HistoryViewModel = hiltViewModel()) {
    val range by vm.range.collectAsStateWithLifecycle()
    val charts by vm.charts.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                HistoryRange.entries.forEachIndexed { i, r ->
                    SegmentedButton(selected = r == range, onClick = { vm.range.value = r }, shape = SegmentedButtonDefaults.itemShape(i, HistoryRange.entries.size)) {
                        Text(stringResource(r.label))
                    }
                }
            }
        }
        val c = charts
        if (c == null || c.isEmpty) {
            item { Text(stringResource(R.string.hist_empty)) }
        } else {
            // Labels use the range the points were computed for, never a newer tab selection.
            val zone = ZoneId.systemDefault()
            val pattern = DateTimeFormatter.ofPattern(if (c.range == HistoryRange.Day) "HH:mm" else "d.M.")
            val bucketMillis = c.range.bucketMinutes * 60_000L
            val label = { x: Double -> pattern.format(Instant.ofEpochMilli(c.startMillis + (x * bucketMillis).toLong()).atZone(zone)) }
            item(key = "temps") {
                SectionCard(stringResource(R.string.hist_temperatures)) {
                    LineChart(c.temperatures, label, labelEvery = c.range.labelEvery)
                    Text(stringResource(R.string.hist_legend), style = MaterialTheme.typography.bodySmall)
                }
            }
            item(key = "modulation") { SectionCard(stringResource(R.string.hist_modulation)) { LineChart(listOf(c.modulation), label, labelEvery = c.range.labelEvery) } }
            item(key = "burner") { SectionCard(stringResource(R.string.hist_burner)) { LineChart(listOf(c.burner), label, labelEvery = c.range.labelEvery) } }
        }
    }
}
