package com.vigasboiler.app.ui.device

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.vigasboiler.app.R
import com.vigasboiler.app.feature.PropertyValue
import com.vigasboiler.app.ui.AppViewModel
import com.vigasboiler.app.ui.common.LabeledRow
import com.vigasboiler.app.ui.common.SectionCard

/** Installation, boiler and gateway data from the discovery dump (no API calls) and the snapshot. */
private data class Equipment(val installation: JsonObject?, val gateway: JsonObject?, val devices: List<JsonObject>)

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

private suspend fun loadEquipment(context: Context): Equipment? = withContext(Dispatchers.IO) {
    val dir = File(context.filesDir, "debug-dump")
    val file = File(dir, "02_installations.json").takeIf { it.exists() } ?: return@withContext null
    val installation = runCatching { ((Json.parseToJsonElement(file.readText()) as JsonObject)["data"] as JsonArray).first() as JsonObject }.getOrNull()
    val gateway = (installation?.get("gateways") as? JsonArray)?.firstOrNull() as? JsonObject
    val devices = (gateway?.get("devices") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    Equipment(installation, gateway, devices)
}

@Composable
fun DeviceInfoScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val equipment by produceState<Equipment?>(null) { value = loadEquipment(context) }
    var reveal by remember { mutableStateOf(false) }
    fun mask(serial: String?) = when {
        serial == null -> "–"
        reveal -> serial
        else -> "•••• " + serial.takeLast(4)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.dev_serial), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { reveal = !reveal }) { Text(stringResource(if (reveal) R.string.dev_hide else R.string.dev_show)) }
            }
        }
        val eq = equipment
        if (eq == null) {
            item { Text(stringResource(R.string.dev_no_dump), style = MaterialTheme.typography.bodyMedium) }
        } else {
            eq.installation?.let { inst ->
                item {
                    SectionCard(stringResource(R.string.dev_installation), icon = R.drawable.ic_home) {
                        inst.str("description")?.let { LabeledRow(stringResource(R.string.dev_name), it) }
                        inst.str("installationType")?.let { LabeledRow(stringResource(R.string.dev_type), it) }
                        inst.str("aggregatedStatus")?.let { LabeledRow(stringResource(R.string.dev_status), it) }
                    }
                }
            }
            eq.devices.firstOrNull { it.str("deviceType") == "heating" }?.let { d ->
                item {
                    SectionCard(stringResource(R.string.dev_boiler), icon = R.drawable.ic_boiler) {
                        d.str("modelId")?.let { LabeledRow(stringResource(R.string.dev_model), it) }
                        LabeledRow(stringResource(R.string.dev_serial), mask(d.str("boilerSerial") ?: snapshot?.get("device.serial")?.text()))
                        d.str("materialNumber")?.let { LabeledRow(stringResource(R.string.dev_material), it) }
                        d.str("status")?.let { LabeledRow(stringResource(R.string.dev_status), it) }
                    }
                }
            }
            eq.gateway?.let { g ->
                item {
                    SectionCard(stringResource(R.string.dev_gateway), icon = R.drawable.ic_wifi) {
                        g.str("gatewayType")?.let { LabeledRow(stringResource(R.string.dev_type), it) }
                        g.str("version")?.let { LabeledRow(stringResource(R.string.dev_version), it) }
                        LabeledRow(stringResource(R.string.dev_serial), mask(g.str("serial")))
                        g.str("aggregatedStatus")?.let { LabeledRow(stringResource(R.string.dev_status), it) }
                    }
                }
            }
        }
        // Firmware of each device as reported by the gateway.
        val list = (snapshot?.gatewayFeature("gateway.devices")?.properties?.get("devices") as? PropertyValue.ObjectValue)?.value as? JsonArray
        list?.filterIsInstance<JsonObject>()?.forEach { d ->
            item {
                SectionCard(d.str("name") ?: d.str("id"), icon = if (d.str("type") == "tcu") R.drawable.ic_wifi else R.drawable.ic_boiler) {
                    d.str("modelId")?.let { LabeledRow(stringResource(R.string.dev_model), it) }
                    // Fingerprint "0041_tcu;<serial>;37.501.2140.101;37.7006.200.6020": keep only version numbers.
                    d.str("fingerprint")?.split(';')?.filter { it.matches(Regex("\\d+(\\.\\d+)+")) }?.takeIf { it.isNotEmpty() }
                        ?.let { LabeledRow(stringResource(R.string.dev_version), it.joinToString(" / ")) }
                    d.str("type")?.let { LabeledRow(stringResource(R.string.dev_type), it) }
                    d.str("status")?.let { LabeledRow(stringResource(R.string.dev_status), it) }
                }
            }
        }
    }
}
