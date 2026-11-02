package com.vigasboiler.app.ui.heating

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import com.vigasboiler.app.ui.common.appear
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonPrimitive
import com.vigasboiler.app.R
import com.vigasboiler.app.feature.Day
import com.vigasboiler.app.feature.Feature
import com.vigasboiler.app.feature.FeatureSnapshot
import com.vigasboiler.app.feature.PropertyValue
import com.vigasboiler.app.feature.Schedule
import com.vigasboiler.app.feature.ScheduleRules
import com.vigasboiler.app.ui.AppViewModel
import com.vigasboiler.app.ui.Change
import com.vigasboiler.app.ui.Navigate
import com.vigasboiler.app.ui.common.AppCard
import com.vigasboiler.app.ui.common.LabeledRow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.vigasboiler.app.ui.common.SectionCard
import com.vigasboiler.app.ui.common.StatusChip
import com.vigasboiler.app.ui.common.Stepper
import com.vigasboiler.app.ui.common.formatNumber
import com.vigasboiler.app.ui.common.formatWithUnit
import com.vigasboiler.app.ui.common.prettify

private const val CIRCUIT = "heating.circuits.0"

@Composable
fun HeatingScreen(vm: AppViewModel, navigate: Navigate) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val s = snapshot ?: run {
        Text(stringResource(R.string.dash_no_data), Modifier.padding(16.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        s["$CIRCUIT.operating.modes.active"]?.let { item { Box(Modifier.appear(0)) { ModeSection(vm, it) } } }
        item { Box(Modifier.appear(1)) { ProgramsSection(vm, s) } }
        s["$CIRCUIT.heating.schedule"]?.let { item { Box(Modifier.appear(2)) { ScheduleSummary(it) { day -> navigate("schedule:${it.name}|${day?.key.orEmpty()}") } } } }
        s["$CIRCUIT.heating.curve"]?.let { item { Box(Modifier.appear(3)) { CurveSection(vm, it, s) } } }
        item { Box(Modifier.appear(4)) { HolidaySection(vm, s) } }
        item { Box(Modifier.appear(5)) { LimitsSection(s) } }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ModeSection(vm: AppViewModel, feature: Feature) {
    val command = feature.commands["setMode"]
    val options = command?.params?.get("mode")?.constraints?.enum.orEmpty()
    val current = feature.text()
    val label = stringResource(R.string.change_mode)
    SectionCard(stringResource(R.string.heat_mode), icon = R.drawable.ic_tune) {
        if (options.isEmpty()) {
            Text(current?.let(::prettify) ?: "–", style = MaterialTheme.typography.titleLarge)
        } else {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = mode == current,
                        onClick = {
                            if (mode != current) {
                                vm.request(feature, "setMode", mapOf("mode" to JsonPrimitive(mode)), listOf(Change(label, prettify(current ?: "–"), prettify(mode))))
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        enabled = command?.isExecutable == true,
                    ) { Text(prettify(mode)) }
                }
            }
        }
    }
}

@Composable
private fun ProgramsSection(vm: AppViewModel, s: FeatureSnapshot) {
    val order = listOf("comfort", "normal", "reduced")
    val programs = s.device.filter {
        it.name.startsWith("$CIRCUIT.operating.programs.") && it.isEnabled && it.commands["setTemperature"]?.isExecutable == true
    }.sortedBy { order.indexOf(it.name.substringAfterLast('.')).let { i -> if (i < 0) 99 else i } }
    if (programs.isEmpty()) return
    SectionCard(stringResource(R.string.heat_programs), icon = R.drawable.ic_thermometer) {
        programs.forEachIndexed { i, program ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            ProgramRow(vm, program)
        }
    }
}

@Composable
private fun ProgramRow(vm: AppViewModel, feature: Feature) {
    val command = feature.commands.getValue("setTemperature")
    val param = command.params.values.firstOrNull { it.type == "number" } ?: return
    val current = feature.number("temperature") ?: return
    var edited by remember(current) { mutableDoubleStateOf(current) }
    val name = prettify(feature.name.substringAfterLast('.'))
    val label = stringResource(R.string.change_program_temp, name)
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                if (feature.bool("active") == true) StatusChip(stringResource(R.string.heat_active), null, MaterialTheme.colorScheme.primary)
            }
            Stepper(edited, param.constraints, feature.unit("temperature"), onChange = { edited = it })
        }
        AnimatedVisibility(edited != current, Modifier.align(Alignment.End), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            FilledTonalButton(
                onClick = {
                    vm.request(
                        feature, "setTemperature", mapOf(param.name to JsonPrimitive(edited)),
                        listOf(Change(label, formatWithUnit(current, "celsius"), formatWithUnit(edited, "celsius"))),
                    )
                },
            ) { Text(stringResource(R.string.heat_set_to, formatWithUnit(edited, "celsius"))) }
        }
    }
}

@Composable
private fun ScheduleSummary(feature: Feature, onEdit: (Day?) -> Unit) {
    val schedule = (feature.properties["entries"] as? PropertyValue.ObjectValue)?.let { Schedule.fromJson(it.value) } ?: return
    val rules = feature.commands["setSchedule"]?.params?.get("newSchedule")?.constraints?.let(ScheduleRules::from)
    val editable = feature.commands["setSchedule"]?.isExecutable == true
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_calendar), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.heat_schedule), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (editable) TextButton(onClick = { onEdit(null) }) { Text(stringResource(R.string.sched_edit_short)) }
        }
        ScheduleNowRow(schedule, rules?.defaultMode ?: "reduced")
        WeekColumns(schedule, onDayClick = if (editable) { d -> onEdit(d) } else null)
        ModeLegend(rules?.modes ?: listOf("normal"), rules?.defaultMode)
    }
}

@Composable
private fun CurveSection(vm: AppViewModel, feature: Feature, s: FeatureSnapshot) {
    val command = feature.commands["setCurve"]
    val slopeNow = feature.number("slope") ?: return
    val shiftNow = feature.number("shift") ?: return
    var slope by remember(slopeNow) { mutableDoubleStateOf(slopeNow) }
    var shift by remember(shiftNow) { mutableDoubleStateOf(shiftNow) }
    val levels = s["$CIRCUIT.temperature.levels"]
    val slopeLabel = stringResource(R.string.heat_slope)
    val shiftLabel = stringResource(R.string.heat_shift)
    val changed = slope != slopeNow || shift != shiftNow

    // The curve is drawn for a room setpoint; default to the active program's temperature.
    val programs = listOf("reduced", "normal", "comfort").mapNotNull { name ->
        s["$CIRCUIT.operating.programs.$name"]?.number("temperature")?.let { name to it }
    }
    val active = s["$CIRCUIT.operating.programs.active"]?.text()
    var setpointProgram by remember { mutableStateOf(programs.firstOrNull { it.first == active }?.first ?: "normal") }
    val room = programs.firstOrNull { it.first == setpointProgram }?.second ?: 20.0

    SectionCard(stringResource(R.string.heat_curve), icon = R.drawable.ic_nav_stats) {
        if (programs.size > 1) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                programs.forEachIndexed { i, (name, temp) ->
                    SegmentedButton(
                        selected = name == setpointProgram,
                        onClick = { setpointProgram = name },
                        shape = SegmentedButtonDefaults.itemShape(i, programs.size),
                    ) { Text("${prettify(name)} ${formatNumber(temp, 0)}°", maxLines = 1) }
                }
            }
        }
        HeatingCurveChart(
            CurveParams(
                slope = slopeNow, shift = shiftNow,
                editedSlope = slope, editedShift = shift,
                roomSetpoint = room,
                minSupply = levels?.number("min"), maxSupply = levels?.number("max"),
            ),
            outsideNow = s["heating.sensors.temperature.outside"]?.number(),
            supplyNow = s["$CIRCUIT.sensors.temperature.supply"]?.number() ?: s["heating.boiler.sensors.temperature.commonSupply"]?.number(),
        )
        command?.params?.get("slope")?.let { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(slopeLabel, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.curve_slope_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Stepper(slope, p.constraints, null, onChange = { slope = it }, enabled = command.isExecutable)
            }
        }
        command?.params?.get("shift")?.let { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(shiftLabel, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.curve_shift_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Stepper(shift, p.constraints, null, onChange = { shift = it }, enabled = command.isExecutable)
            }
        }
        AnimatedVisibility(changed && command?.isExecutable == true, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { slope = slopeNow; shift = shiftNow }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.sched_discard)) }
                Button(
                    modifier = Modifier.weight(2f),
                    onClick = {
                        vm.request(
                            feature, "setCurve", mapOf("slope" to JsonPrimitive(slope), "shift" to JsonPrimitive(shift)),
                            listOf(Change(slopeLabel, formatNumber(slopeNow), formatNumber(slope)), Change(shiftLabel, formatNumber(shiftNow), formatNumber(shift))),
                        )
                    },
                ) { Text(stringResource(R.string.heat_curve_apply)) }
            }
        }
    }
}

@Composable
private fun HolidaySection(vm: AppViewModel, s: FeatureSnapshot) {
    val programs = listOf(
        "heating.operating.programs.holiday" to R.string.heat_holiday,
        "heating.operating.programs.holidayAtHome" to R.string.heat_holiday_home,
    ).mapNotNull { (name, label) -> s[name]?.let { it to label } }
    if (programs.isEmpty()) return
    SectionCard(stringResource(R.string.heat_holidays), icon = R.drawable.ic_calendar) {
        programs.forEachIndexed { i, (feature, label) ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            HolidayRow(vm, feature, stringResource(label))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HolidayRow(vm: AppViewModel, feature: Feature, title: String) {
    var picking by remember { mutableStateOf(false) }
    val active = feature.bool("active") == true
    val start = feature.text("start").orEmpty()
    val end = feature.text("end").orEmpty()
    val label = stringResource(R.string.change_holiday, title)
    val none = stringResource(R.string.heat_holiday_inactive)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                if (start.isNotEmpty()) stringResource(R.string.heat_holiday_range, start, end) else none,
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (active || start.isNotEmpty()) {
            TextButton(
                enabled = feature.commands["unschedule"]?.isExecutable == true,
                onClick = { vm.request(feature, "unschedule", emptyMap(), listOf(Change(label, "$start – $end", none))) },
            ) { Text(stringResource(R.string.heat_holiday_cancel)) }
        } else {
            TextButton(onClick = { picking = true }, enabled = feature.commands["schedule"]?.isExecutable == true) {
                Text(stringResource(R.string.heat_holiday_schedule))
            }
        }
    }
    if (picking) {
        val state = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null,
                    onClick = {
                        picking = false
                        val from = utcDate(state.selectedStartDateMillis!!).toString()
                        val to = utcDate(state.selectedEndDateMillis!!).toString()
                        vm.request(feature, "schedule", mapOf("start" to JsonPrimitive(from), "end" to JsonPrimitive(to)), listOf(Change(label, none, "$from – $to")))
                    },
                ) { Text(stringResource(R.string.sched_ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) {
            DateRangePicker(state, Modifier.height(480.dp))
        }
    }
}

/** Date pickers report UTC midnight. */
private fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun LimitsSection(s: FeatureSnapshot) {
    val levels = s["$CIRCUIT.temperature.levels"]
    SectionCard(stringResource(R.string.heat_limits), icon = R.drawable.ic_pump) {
        levels?.let {
            LabeledRow(stringResource(R.string.heat_min_supply), formatWithUnit(it.number("min"), it.unit("min")), R.drawable.ic_thermometer)
            LabeledRow(stringResource(R.string.heat_max_supply), formatWithUnit(it.number("max"), it.unit("max")), R.drawable.ic_thermometer)
        }
        s["$CIRCUIT.circulation.pump"]?.text("status")?.let { LabeledRow(stringResource(R.string.heat_circulation_pump), prettify(it), R.drawable.ic_pump) }
        s["heating.boiler.pumps.internal"]?.text("status")?.let { LabeledRow(stringResource(R.string.heat_internal_pump), prettify(it), R.drawable.ic_pump) }
        s["$CIRCUIT.frostprotection"]?.text("status")?.let { LabeledRow(stringResource(R.string.heat_frost), prettify(it), R.drawable.ic_drop) }
        if (levels != null && levels.commands.values.none { it.isExecutable }) {
            Text(stringResource(R.string.heat_limits_readonly), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
