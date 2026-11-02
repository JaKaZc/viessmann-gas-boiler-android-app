package com.vigasboiler.app.ui.heating

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vigasboiler.app.R
import com.vigasboiler.app.feature.Day
import com.vigasboiler.app.feature.PropertyValue
import com.vigasboiler.app.feature.Schedule
import com.vigasboiler.app.feature.ScheduleEntry
import com.vigasboiler.app.feature.ScheduleRules
import com.vigasboiler.app.ui.AppViewModel
import com.vigasboiler.app.ui.Change
import com.vigasboiler.app.ui.common.prettify

@Composable
fun ScheduleEditorScreen(vm: AppViewModel, featureName: String, initialDay: Day? = null) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val feature = snapshot?.get(featureName) ?: return
    val command = feature.commands["setSchedule"] ?: return
    val param = command.params.values.firstOrNull { it.type == "Schedule" } ?: return
    val rules = ScheduleRules.from(param.constraints)
    val original = (feature.properties["entries"] as? PropertyValue.ObjectValue)?.let { Schedule.fromJson(it.value) } ?: Schedule.EMPTY
    var schedule by remember(original) { mutableStateOf(original) }
    val (today, _) = rememberNow()
    var selectedKey by rememberSaveable { mutableStateOf((initialDay ?: today).key) }
    val selected = Day.entries.first { it.key == selectedKey }
    var editing by remember { mutableStateOf<Pair<Day, Int?>?>(null) } // (day, index or null = new)
    var copyFrom by remember { mutableStateOf<Day?>(null) }
    val violations = rules.validate(schedule)
    val dirty = schedule != original
    val changedLabel = stringResource(R.string.sched_changed_days)

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DaySelector(selected, today, changed = Day.entries.filter { schedule.day(it) != original.day(it) }.toSet()) { selectedKey = it.key }

            AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally { w -> if (forward) w / 3 else -w / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { w -> if (forward) -w / 3 else w / 3 } + fadeOut())
                },
                label = "day",
            ) { day ->
                val entries = schedule.day(day)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DayDial(entries, showNow = day == today, modifier = Modifier.fillMaxWidth(0.78f).align(Alignment.CenterHorizontally)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(prettify(day.name.lowercase()), style = MaterialTheme.typography.headlineSmall)
                            Text(
                                stringResource(R.string.sched_periods, entries.size, rules.maxEntries ?: entries.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (entries.isEmpty()) {
                        Text(
                            stringResource(R.string.sched_empty_day, prettify(rules.defaultMode ?: "reduced")),
                            Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    entries.forEachIndexed { i, e -> PeriodCard(e) { editing = day to i } }
                    violations.filter { it.day == day }.forEach {
                        Text("${it.kind} ${it.detail}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalButton(
                        onClick = { editing = day to null },
                        enabled = rules.maxEntries == null || entries.size < rules.maxEntries,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("+  " + stringResource(R.string.sched_add)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copyFrom = day }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.sched_copy_short)) }
                        OutlinedButton(
                            onClick = { schedule = schedule.copyToAll(day) },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.sched_copy_all_short)) }
                    }
                }
            }
            Text(
                stringResource(R.string.sched_rules, rules.maxEntries ?: 99, rules.resolutionMinutes ?: 1, rules.modes.joinToString { prettify(it) }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModeLegend(rules.modes, rules.defaultMode)
        }

        // Save bar slides up as soon as something changed.
        AnimatedVisibility(
            visible = dirty,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Surface(
                Modifier.fillMaxWidth().padding(12.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 6.dp,
            ) {
                Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (violations.isEmpty()) stringResource(R.string.sched_unsaved) else stringResource(R.string.sched_errors),
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TextButton(onClick = { schedule = original }) { Text(stringResource(R.string.sched_discard), color = MaterialTheme.colorScheme.inversePrimary) }
                    Button(
                        enabled = violations.isEmpty() && command.isExecutable,
                        onClick = {
                            val changed = Day.entries.filter { schedule.day(it) != original.day(it) }
                            val describe = { s: Schedule -> changed.joinToString("; ") { d -> prettify(d.key) + " " + s.day(d).joinToString { "${it.start}–${it.end}" }.ifEmpty { "–" } } }
                            // After a confirmed save the snapshot updates and `original` follows, hiding this bar.
                            vm.request(feature, "setSchedule", mapOf(param.name to schedule.toJson()), listOf(Change(changedLabel, describe(original), describe(schedule))))
                        },
                    ) { Text(stringResource(R.string.sched_save)) }
                }
            }
        }
    }

    editing?.let { (day, index) ->
        val existing = index?.let { schedule.day(day).getOrNull(it) }
        EntryDialog(
            initial = existing ?: ScheduleEntry(rules.modes.firstOrNull() ?: "normal", "06:00", "22:00"),
            modes = rules.modes,
            resolution = rules.resolutionMinutes ?: 1,
            canDelete = existing != null,
            onDismiss = { editing = null },
            onDelete = {
                schedule = schedule.withDay(day, schedule.day(day).filterIndexed { i, _ -> i != index })
                editing = null
            },
            onSave = { entry ->
                val list = schedule.day(day).toMutableList()
                if (index == null) list += entry else list[index] = entry
                schedule = schedule.withDay(day, list)
                editing = null
            },
        )
    }

    copyFrom?.let { from ->
        CopyDialog(
            from = from,
            onDismiss = { copyFrom = null },
            onCopy = { targets ->
                schedule = targets.fold(schedule) { acc, d -> acc.copyDay(from, d) }
                copyFrom = null
            },
        )
    }
}

/** Seven round day buttons; today is outlined, changed days get a dot. */
@Composable
private fun DaySelector(selected: Day, today: Day, changed: Set<Day>, onSelect: (Day) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Day.entries.forEach { d ->
            val isSelected = d == selected
            val bg by animateColorAsState(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, label = "dayBg")
            val fg by animateColorAsState(if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, label = "dayFg")
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(bg).clickable { onSelect(d) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        prettify(d.key).take(2),
                        color = fg,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (d == today) FontWeight.Black else FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.size(6.dp).clip(CircleShape)
                        .background(if (d in changed) MaterialTheme.colorScheme.tertiary else androidx.compose.ui.graphics.Color.Transparent),
                )
            }
        }
    }
}

/** One switching period: colour strip, time range and mode. */
@Composable
private fun PeriodCard(entry: ScheduleEntry, onClick: () -> Unit) {
    val color = modeColor(entry.mode)
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(8.dp).fillMaxHeight().background(color))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text("${entry.start} – ${entry.end}", style = MaterialTheme.typography.titleLarge)
                Text(prettify(entry.mode), style = MaterialTheme.typography.bodyMedium, color = color)
            }
            Text("›", Modifier.padding(end = 16.dp), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryDialog(
    initial: ScheduleEntry,
    modes: List<String>,
    resolution: Int,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (ScheduleEntry) -> Unit,
) {
    var mode by remember { mutableStateOf(initial.mode) }
    var start by remember { mutableStateOf(initial.start) }
    var end by remember { mutableStateOf(initial.end) }
    var picking by remember { mutableStateOf<String?>(null) } // "start" | "end"
    var modeMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sched_title, "$start – $end")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { picking = "start" }) { Text(stringResource(R.string.sched_start) + " " + start) }
                    OutlinedButton(onClick = { picking = "end" }) { Text(stringResource(R.string.sched_end) + " " + end) }
                }
                FilterChip(selected = end == "24:00", onClick = { end = "24:00" }, label = { Text(stringResource(R.string.sched_end_of_day)) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sched_mode) + ": ")
                    TextButton(onClick = { modeMenu = true }) { Text(mode) }
                    DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        modes.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { mode = m; modeMenu = false }) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(ScheduleEntry(mode, start, end)) }) { Text(stringResource(R.string.sched_ok)) } },
        dismissButton = {
            Row {
                if (canDelete) TextButton(onClick = onDelete) { Text(stringResource(R.string.sched_delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )

    picking?.let { which ->
        val minutes = Schedule.minutesOf(if (which == "start") start else end)?.coerceAtMost(23 * 60 + 59) ?: 0
        val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = null },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    // Snap to the schedule resolution (e.g. 10 minutes).
                    val total = state.hour * 60 + (state.minute / resolution) * resolution
                    val value = Schedule.format(total)
                    if (which == "start") start = value else end = value
                    picking = null
                }) { Text(stringResource(R.string.sched_ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun CopyDialog(from: Day, onDismiss: () -> Unit, onCopy: (List<Day>) -> Unit) {
    var targets by remember { mutableStateOf(emptySet<Day>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sched_copy_to) + " (" + from.key + ")") },
        text = {
            Column {
                Day.entries.filter { it != from }.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = d in targets, onCheckedChange = { targets = if (it) targets + d else targets - d })
                        Text(d.key)
                    }
                }
                TextButton(onClick = { onCopy(Day.entries.filter { it != from }) }) { Text(stringResource(R.string.sched_copy_all)) }
            }
        },
        confirmButton = { TextButton(enabled = targets.isNotEmpty(), onClick = { onCopy(targets.toList()) }) { Text(stringResource(R.string.sched_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
