package com.vigasboiler.app.ui.explorer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import com.vigasboiler.app.R
import com.vigasboiler.app.feature.Command
import com.vigasboiler.app.feature.CommandParam
import com.vigasboiler.app.feature.CommandValidator
import com.vigasboiler.app.feature.Feature
import com.vigasboiler.app.feature.PropertyValue
import com.vigasboiler.app.ui.AppViewModel
import com.vigasboiler.app.ui.Change
import com.vigasboiler.app.ui.Navigate
import com.vigasboiler.app.ui.common.LabeledRow
import com.vigasboiler.app.ui.common.SectionCard
import com.vigasboiler.app.ui.common.formatNumber
import com.vigasboiler.app.ui.common.formatProperty
import com.vigasboiler.app.ui.common.prettyJson

@Composable
fun FeatureDetailScreen(vm: AppViewModel, name: String, navigate: Navigate) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val feature = snapshot?.let { it[name] ?: it.gatewayFeature(name) } ?: return
    var showRaw by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard(stringResource(R.string.exp_properties)) {
                if (!feature.isEnabled) Text(stringResource(R.string.exp_disabled), color = MaterialTheme.colorScheme.error)
                feature.deprecated?.let { Text(stringResource(R.string.exp_deprecated) + ": " + it.toString(), style = MaterialTheme.typography.bodySmall) }
                feature.properties.forEach { (k, v) ->
                    when (v) {
                        is PropertyValue.ArrayValue, is PropertyValue.ObjectValue, is PropertyValue.Unknown -> {
                            Text("$k (${v.type})", style = MaterialTheme.typography.labelLarge)
                            JsonBlock(
                                when (v) {
                                    is PropertyValue.ArrayValue -> kotlinx.serialization.json.JsonArray(v.items)
                                    is PropertyValue.ObjectValue -> v.value
                                    is PropertyValue.Unknown -> v.raw
                                    else -> JsonPrimitive("")
                                },
                            )
                        }
                        else -> LabeledRow(k, formatProperty(v))
                    }
                }
                feature.timestamp?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        }
        if (feature.commands.isNotEmpty()) {
            item { Text(stringResource(R.string.exp_commands), style = MaterialTheme.typography.titleMedium) }
            feature.commands.values.forEach { command -> item(key = command.name) { CommandForm(vm, feature, command, navigate) } }
        }
        item {
            TextButton(onClick = { showRaw = !showRaw }) { Text(stringResource(R.string.exp_raw)) }
            if (showRaw) JsonBlock(feature.raw)
        }
    }
}

@Composable
private fun JsonBlock(json: JsonElement) {
    Text(
        prettyJson(json),
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Input controls generated from the command's `params` constraints. */
@Composable
private fun CommandForm(vm: AppViewModel, feature: Feature, command: Command, navigate: Navigate) {
    val inputs = remember(feature) { mutableStateMapOf<String, String>().apply { command.params.values.forEach { put(it.name, initialText(feature, it)) } } }
    val values: Map<String, JsonElement> = command.params.values.mapNotNull { p -> toJson(p, inputs[p.name].orEmpty())?.let { p.name to it } }.toMap()
    val errors = if (command.params.isEmpty()) emptyList() else CommandValidator.validate(command, values)

    SectionCard(command.name) {
        if (!command.isExecutable) Text(stringResource(R.string.exp_not_executable), color = MaterialTheme.colorScheme.error)
        if (command.params.isEmpty()) Text(stringResource(R.string.exp_no_params), style = MaterialTheme.typography.bodySmall)
        command.params.values.forEach { p ->
            val paramErrors = errors.filter { it.param == p.name }
            ParamInput(p, inputs[p.name].orEmpty(), enabled = command.isExecutable, onChange = { inputs[p.name] = it }, onOpenScheduleEditor = { navigate("schedule:${feature.name}") })
            describe(p)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            paramErrors.forEach { Text("${it.kind} ${it.detail}".trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        Button(
            enabled = command.isExecutable && errors.isEmpty(),
            onClick = {
                val changes = command.params.values.map { p ->
                    Change(p.name, currentFor(feature, p)?.let(::shortJson) ?: "–", values[p.name]?.let(::shortJson) ?: "–")
                }
                vm.request(feature, command.name, values, changes)
            },
        ) { Text(stringResource(R.string.exp_send)) }
    }
}

@Composable
private fun ParamInput(p: CommandParam, text: String, enabled: Boolean, onChange: (String) -> Unit, onOpenScheduleEditor: () -> Unit) {
    val enum = p.constraints.enum
    when {
        p.type == "boolean" -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, Modifier.weight(1f))
            Switch(text == "true", { onChange(it.toString()) }, enabled = enabled)
        }
        p.type == "Schedule" -> OutlinedButton(onClick = onOpenScheduleEditor, enabled = enabled) { Text(stringResource(R.string.heat_schedule_edit)) }
        enum != null -> {
            var open by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, Modifier.weight(1f))
                OutlinedButton(onClick = { open = true }, enabled = enabled) { Text(text.ifEmpty { "…" }) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    enum.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onChange(option); open = false }) }
                }
            }
        }
        else -> OutlinedTextField(
            value = text,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text(p.name + if (p.required) " *" else "") },
            singleLine = p.type != "unknown",
            keyboardOptions = KeyboardOptions(keyboardType = if (p.type == "number") KeyboardType.Decimal else KeyboardType.Text),
        )
    }
}

/** Human summary of the constraints, e.g. "3 … 37, step 1". */
private fun describe(p: CommandParam): String? {
    val c = p.constraints
    val parts = buildList {
        if (c.min != null || c.max != null) add("${c.min?.let { formatNumber(it, 3) } ?: "…"} … ${c.max?.let { formatNumber(it, 3) } ?: "…"}")
        c.stepping?.let { add("step ${formatNumber(it, 3)}") }
        c.regEx?.let { add(if (it.contains("\\d]{4}-")) "YYYY-MM-DD" else it) }
        c.minLength?.let { add("min length $it") }
        c.maxLength?.let { add("max length $it") }
    }
    return parts.joinToString(", ").ifEmpty { null }
}

private fun toJson(p: CommandParam, text: String): JsonElement? = when (p.type) {
    "number" -> text.replace(',', '.').toDoubleOrNull()?.let { if (it % 1.0 == 0.0) JsonPrimitive(it.toLong()) else JsonPrimitive(it) }
    "boolean" -> JsonPrimitive(text == "true")
    "string" -> text.takeIf { it.isNotEmpty() || p.required }?.let(::JsonPrimitive)
    "Schedule" -> null // edited in the schedule editor
    else -> text.takeIf { it.isNotBlank() }?.let { runCatching { Json.parseToJsonElement(it) }.getOrElse { _ -> JsonPrimitive(text) } }
}

/** The property that most likely holds the current value of [p] (same name, or `value`). */
private fun currentFor(feature: Feature, p: CommandParam): JsonElement? {
    val candidates = listOf(p.name, "value", if (p.name == "targetTemperature") "temperature" else p.name, if (p.name == "mode") "value" else p.name)
    val prop = candidates.firstNotNullOfOrNull { feature.properties[it] } ?: return null
    return when (prop) {
        is PropertyValue.Number -> JsonPrimitive(prop.value)
        is PropertyValue.Text -> JsonPrimitive(prop.value)
        is PropertyValue.Bool -> JsonPrimitive(prop.value)
        else -> null
    }
}

private fun initialText(feature: Feature, p: CommandParam): String {
    val current = currentFor(feature, p) as? JsonPrimitive ?: return ""
    if (p.type == "number") return current.content.toDoubleOrNull()?.let { formatPlain(it) } ?: ""
    return current.content
}

private fun formatPlain(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

private fun shortJson(e: JsonElement): String = (e as? JsonPrimitive)?.content ?: e.toString().take(80)
