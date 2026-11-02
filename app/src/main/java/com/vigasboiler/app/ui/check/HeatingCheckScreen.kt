package com.vigasboiler.app.ui.check

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.vigasboiler.app.R
import com.vigasboiler.app.analysis.HeatingCheck
import com.vigasboiler.app.analysis.HeatingCheck.Finding
import com.vigasboiler.app.analysis.HeatingCheck.Rating
import com.vigasboiler.app.analysis.HeatingCheck.Section
import com.vigasboiler.app.analysis.HeatingCheckRepository
import com.vigasboiler.app.ui.Navigate
import com.vigasboiler.app.ui.common.AppCard
import com.vigasboiler.app.ui.common.IconBadge
import com.vigasboiler.app.ui.common.StatusChip
import com.vigasboiler.app.ui.common.formatNumber
import com.vigasboiler.app.ui.theme.AppColors

@HiltViewModel
class HeatingCheckViewModel @Inject constructor(private val repository: HeatingCheckRepository) : ViewModel() {
    val readiness: StateFlow<HeatingCheck.Readiness?> = repository.readiness.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _report = MutableStateFlow<HeatingCheck.Report?>(null)
    val report: StateFlow<HeatingCheck.Report?> = _report.asStateFlow()

    fun run() {
        if (_report.value != null) return
        viewModelScope.launch { _report.value = repository.run() }
    }
}

private val Pages = listOf(Section.Burner, Section.Comfort, Section.Efficiency, Section.System)

/**
 * Wizard: intro (or how many days are still missing) → one page per section → summary. The
 * check needs [HeatingCheck.REQUIRED_DAYS] days of history; it never sends anything to the boiler.
 */
@Composable
fun HeatingCheckScreen(navigate: Navigate, onDone: () -> Unit, vm: HeatingCheckViewModel = hiltViewModel()) {
    val readiness by vm.readiness.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val r = readiness ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    if (!r.ready) return Waiting(r)
    LaunchedEffect(Unit) { vm.run() }
    val rep = report ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.check_running))
        }
    }

    var step by rememberSaveable { mutableIntStateOf(0) }
    val last = Pages.size + 1
    BackHandler(enabled = step > 0) { step-- }
    Column(Modifier.fillMaxSize()) {
        if (step > 0) {
            LinearProgressIndicator(progress = { step / last.toFloat() }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally { it / 4 * dir } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 * dir } + fadeOut())
            },
            modifier = Modifier.weight(1f),
            label = "check",
        ) { s ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (s) {
                    0 -> Intro(rep)
                    last -> Summary(rep, navigate)
                    else -> SectionPage(Pages[s - 1], rep.sections[Pages[s - 1]].orEmpty(), navigate)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) TextButton(onClick = { step-- }) { Text(stringResource(R.string.check_back)) }
            Spacer(Modifier.weight(1f))
            if (step in 1 until last) Text(stringResource(R.string.check_step, step, Pages.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            when (step) {
                0 -> Button(onClick = { step = 1 }) { Text(stringResource(R.string.check_start)) }
                last -> Button(onClick = onDone) { Text(stringResource(R.string.check_done)) }
                else -> Button(onClick = { step++ }) { Text(stringResource(R.string.check_next)) }
            }
        }
    }
}

@Composable
private fun Waiting(r: HeatingCheck.Readiness) {
    val readyOn = LocalDate.now().plusDays(r.missing.toLong()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(R.drawable.ic_calendar, MaterialTheme.colorScheme.primary, size = 44)
                Column {
                    Text(stringResource(R.string.check_waiting_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.check_waiting_days, r.days, r.required), style = MaterialTheme.typography.headlineSmall)
                }
            }
            LinearProgressIndicator(progress = { r.days / r.required.toFloat() }, Modifier.fillMaxWidth())
            Text(stringResource(R.string.check_waiting_text, readyOn), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.check_waiting_hint, HeatingCheck.MIN_SAMPLES_PER_DAY), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LooksAt()
    }
}

@Composable
private fun Intro(report: HeatingCheck.Report) {
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    fun day(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(format)
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(R.drawable.ic_check, MaterialTheme.colorScheme.primary, size = 44)
            Text(stringResource(R.string.check_title), style = MaterialTheme.typography.headlineSmall)
        }
        Text(stringResource(R.string.check_intro), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.check_period, day(report.fromMillis), day(report.toMillis), report.samples), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LooksAt()
    Text(stringResource(R.string.check_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LooksAt() {
    AppCard {
        Text(stringResource(R.string.check_looks_at), style = MaterialTheme.typography.titleMedium)
        LooksRow(R.drawable.ic_nav_heating, R.string.check_looks_burner)
        LooksRow(R.drawable.ic_thermometer, R.string.check_looks_comfort)
        LooksRow(R.drawable.ic_nav_stats, R.string.check_looks_efficiency)
        LooksRow(R.drawable.ic_gauge, R.string.check_looks_system)
    }
}

@Composable
private fun LooksRow(@DrawableRes icon: Int, @StringRes text: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(icon, MaterialTheme.colorScheme.primary, size = 32)
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionPage(section: Section, findings: List<Finding>, navigate: Navigate) {
    Text(stringResource(sectionTitle(section)), style = MaterialTheme.typography.headlineMedium)
    if (findings.isEmpty()) Text(stringResource(R.string.check_section_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
    findings.forEach { FindingCard(it, navigate) }
}

@StringRes
private fun sectionTitle(section: Section) = when (section) {
    Section.Burner -> R.string.check_section_burner
    Section.Comfort -> R.string.check_section_comfort
    Section.Efficiency -> R.string.check_section_efficiency
    Section.System -> R.string.check_section_system
}

@Composable
private fun Summary(report: HeatingCheck.Report, navigate: Navigate) {
    val todo = report.findings.filter { it.rating == Rating.Tip || it.rating == Rating.Warning }.sortedBy { if (it.rating == Rating.Warning) 0 else 1 }
    Text(stringResource(R.string.check_summary_title), style = MaterialTheme.typography.headlineMedium)
    AppCard {
        Text(
            stringResource(R.string.check_summary_counts, report.count(Rating.Good), report.count(Rating.Tip), report.count(Rating.Warning)),
            style = MaterialTheme.typography.titleMedium,
        )
        if (todo.isEmpty()) Text(stringResource(R.string.check_summary_all_good), style = MaterialTheme.typography.bodyMedium)
    }
    if (todo.isNotEmpty()) {
        Text(stringResource(R.string.check_summary_todo), style = MaterialTheme.typography.titleMedium)
        todo.forEach { FindingCard(it, navigate) }
    }
    Text(stringResource(R.string.check_summary_again), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** What a finding says, resolved to text: title, measured values, explanation, suggestion. */
private class FindingText(
    @DrawableRes val icon: Int,
    val title: String,
    val values: List<String>,
    val text: String,
    val suggestion: String? = null,
    val action: Action? = null,
)

private enum class Action { Heating, Battery }

@Composable
private fun describe(f: Finding): FindingText {
    fun n(v: Double, d: Int = 1) = formatNumber(v, d)
    fun pct(v: Double) = formatNumber(v * 100, 0)
    @Composable fun deviation(v: Double) = when {
        v <= -0.25 -> stringResource(R.string.check_room_below, n(-v))
        v >= 0.25 -> stringResource(R.string.check_room_above, n(v))
        else -> stringResource(R.string.check_room_on)
    }
    return when (f) {
        is Finding.Cycling -> FindingText(
            R.drawable.ic_nav_heating, stringResource(R.string.check_cycling_title),
            listOf(stringResource(R.string.check_cycling_value, n(f.startsPerHour, 2), n(f.startsPerDay, 0), n(f.hoursPerDay))),
            stringResource(if (f.rating == Rating.Good) R.string.check_cycling_good else R.string.check_cycling_bad),
            if (f.rating == Rating.Good) null else stringResource(R.string.check_cycling_suggest),
            if (f.rating == Rating.Good) null else Action.Heating,
        )
        is Finding.Modulation -> FindingText(
            R.drawable.ic_gauge, stringResource(R.string.check_modulation_title),
            listOf(stringResource(R.string.check_modulation_value, n(f.average, 0), pct(f.lowShare))),
            stringResource(if (f.rating == Rating.Good) R.string.check_modulation_good else R.string.check_modulation_low),
            if (f.rating == Rating.Good) null else stringResource(R.string.check_modulation_suggest),
        )
        is Finding.RoomVsTarget -> FindingText(
            R.drawable.ic_thermometer, stringResource(R.string.check_room_title),
            listOfNotNull(
                deviation(f.deviation),
                if (f.coldDeviation != null && f.mildDeviation != null) stringResource(R.string.check_room_split, deviation(f.coldDeviation), deviation(f.mildDeviation)) else null,
            ),
            stringResource(
                when {
                    f.rating == Rating.Good -> R.string.check_room_good
                    f.deviation < 0 -> R.string.check_room_cold
                    else -> R.string.check_room_warm
                },
            ),
            f.curve?.let { c ->
                if (c.slopeTo != c.slopeFrom) {
                    stringResource(R.string.check_room_slope) + " " + stringResource(R.string.check_room_curve_slope, n(c.slopeFrom), n(c.slopeTo))
                } else {
                    stringResource(R.string.check_room_shift) + " " + stringResource(R.string.check_room_curve_shift, n(c.shiftFrom, 0), n(c.shiftTo, 0))
                }
            },
            if (f.curve != null) Action.Heating else null,
        )
        Finding.NoRoomSensor -> FindingText(R.drawable.ic_thermometer, stringResource(R.string.check_room_title), emptyList(), stringResource(R.string.check_room_none))
        is Finding.ComfortBelowNormal -> FindingText(
            R.drawable.ic_tune, stringResource(R.string.check_comfort_title), emptyList(),
            stringResource(R.string.check_comfort_text, n(f.comfort), n(f.normal)), action = Action.Heating,
        )
        is Finding.Setback -> {
            val diff = n(f.normal - f.reduced)
            FindingText(
                R.drawable.ic_calendar, stringResource(R.string.check_setback_title),
                listOfNotNull(stringResource(R.string.check_setback_value, n(f.normal), n(f.reduced)), f.reducedShare?.let { stringResource(R.string.check_setback_share, pct(it)) }),
                when {
                    f.rating == Rating.Good -> stringResource(R.string.check_setback_good, diff)
                    f.normal - f.reduced < 1 -> stringResource(R.string.check_setback_none)
                    else -> stringResource(R.string.check_setback_large, diff)
                },
                action = if (f.rating == Rating.Good) null else Action.Heating,
            )
        }
        is Finding.SupplyTemperature -> FindingText(
            R.drawable.ic_drop, stringResource(R.string.check_supply_title),
            listOf(stringResource(R.string.check_supply_value, n(f.average, 0), n(f.max, 0))),
            stringResource(
                when (f.rating) {
                    Rating.Good -> R.string.check_supply_good
                    Rating.Warning -> R.string.check_supply_high
                    else -> R.string.check_supply_mid
                },
            ),
            action = if (f.rating == Rating.Good) null else Action.Heating,
        )
        is Finding.HeatingLimit -> FindingText(
            R.drawable.ic_thermometer, stringResource(R.string.check_mild_title),
            listOf(stringResource(R.string.check_mild_value, pct(f.mildShare), pct(f.burnerShareWhenMild))),
            stringResource(if (f.rating == Rating.Good) R.string.check_mild_good else R.string.check_mild_bad),
            action = if (f.rating == Rating.Good) null else Action.Heating,
        )
        is Finding.Pressure -> FindingText(
            R.drawable.ic_gauge, stringResource(R.string.check_pressure_title),
            listOf(stringResource(R.string.check_pressure_value, n(f.min), n(f.max))),
            when (f.rating) {
                Rating.Good -> stringResource(R.string.check_pressure_good)
                Rating.Warning -> stringResource(R.string.check_pressure_out, n(f.limitMin), n(f.limitMax))
                else -> stringResource(R.string.check_pressure_drop, n(f.drop), n(f.limitMin))
            },
        )
        is Finding.Gas -> FindingText(
            R.drawable.ic_nav_stats, stringResource(R.string.check_gas_title),
            listOfNotNull(stringResource(R.string.check_gas_value, n(f.cubicMeters)), f.perDegreeDay?.let { stringResource(R.string.check_gas_degree, n(it, 2)) }),
            stringResource(R.string.check_gas_text),
        )
        is Finding.Coverage -> FindingText(
            R.drawable.ic_wifi, stringResource(R.string.check_data_title),
            listOf(stringResource(R.string.check_data_value, pct(f.share), f.samples)),
            stringResource(if (f.rating == Rating.Good) R.string.check_data_good else R.string.check_data_bad),
            action = if (f.rating == Rating.Good) null else Action.Battery,
        )
    }
}

@Composable
private fun FindingCard(f: Finding, navigate: Navigate) {
    val t = describe(f)
    val context = LocalContext.current
    val (color, label, icon) = ratingStyle(f.rating)
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(t.icon, color, size = 36)
            Text(t.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            StatusChip(stringResource(label), icon, color)
        }
        t.values.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
        Text(t.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        t.suggestion?.let { s ->
            Column(
                Modifier.fillMaxWidth().background(color.copy(alpha = 0.10f), MaterialTheme.shapes.small).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.check_suggestion), style = MaterialTheme.typography.labelLarge, color = color)
                Text(s, style = MaterialTheme.typography.bodyMedium)
            }
        }
        when (t.action) {
            Action.Heating -> OutlinedButton(onClick = { navigate("heating") }) { Text(stringResource(R.string.check_open_heating)) }
            Action.Battery -> OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
                Text(stringResource(R.string.check_battery))
            }
            null -> Unit
        }
    }
}

@Composable
private fun ratingStyle(rating: Rating): Triple<Color, Int, Int> {
    val extra = AppColors.extra
    return when (rating) {
        Rating.Good -> Triple(extra.success, R.string.check_rating_good, R.drawable.ic_check)
        Rating.Tip -> Triple(extra.warning, R.string.check_rating_tip, R.drawable.ic_info)
        Rating.Warning -> Triple(MaterialTheme.colorScheme.error, R.string.check_rating_warning, R.drawable.ic_warning)
        Rating.Info -> Triple(MaterialTheme.colorScheme.primary, R.string.check_rating_info, R.drawable.ic_info)
    }
}

/** Shown once when a week of data is collected; the check stays in More either way. */
@Composable
fun HeatingCheckReadyDialog(onRun: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        icon = { Icon(painterResource(R.drawable.ic_check), null) },
        title = { Text(stringResource(R.string.check_ready_title)) },
        text = { Text(stringResource(R.string.check_ready_text)) },
        confirmButton = { Button(onClick = onRun) { Text(stringResource(R.string.check_ready_run)) } },
        dismissButton = { TextButton(onClick = onLater) { Text(stringResource(R.string.check_ready_later)) } },
    )
}
