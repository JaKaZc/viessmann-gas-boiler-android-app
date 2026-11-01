package si.vicare.customapp.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import si.vicare.customapp.R
import si.vicare.customapp.history.HistoryRepository
import si.vicare.customapp.settings.AppSettings
import si.vicare.customapp.settings.SettingsRepository
import si.vicare.customapp.ui.AppViewModel
import si.vicare.customapp.ui.common.SectionCard
import si.vicare.customapp.ui.common.formatNumber
import si.vicare.customapp.work.BackgroundRefresh

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val _csv = MutableStateFlow<File?>(null)
    val csv: StateFlow<File?> = _csv.asStateFlow()

    fun exportCsv() {
        viewModelScope.launch { _csv.value = history.exportCsv(File(context.cacheDir, "exports")) }
    }

    fun csvShared() {
        _csv.value = null
    }

    fun setInterval(minutes: Int) {
        viewModelScope.launch {
            settings.update { it.copy(backgroundIntervalMinutes = minutes) }
            BackgroundRefresh.schedule(context, minutes)
        }
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel, settingsVm: SettingsViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val csv by settingsVm.csv.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(csv) {
        csv?.let {
            share(context, it)
            settingsVm.csvShared()
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard(stringResource(R.string.set_section_display)) {
                SwitchRow(stringResource(R.string.set_show_dhw), settings.showDhw) { v -> vm.updateSettings { it.copy(showDhw = v) } }
                Text(stringResource(R.string.set_show_dhw_hint), style = MaterialTheme.typography.bodySmall)
                SwitchRow(stringResource(R.string.set_dynamic_color), settings.dynamicColor) { v -> vm.updateSettings { it.copy(dynamicColor = v) } }
            }
        }
        item {
            SectionCard(stringResource(R.string.set_section_refresh)) {
                var interval by remember(settings.backgroundIntervalMinutes) { mutableFloatStateOf(settings.backgroundIntervalMinutes.toFloat()) }
                Text(stringResource(R.string.set_interval, interval.toInt()))
                Slider(
                    value = interval,
                    onValueChange = { interval = it },
                    valueRange = AppSettings.INTERVAL_RANGE.first.toFloat()..AppSettings.INTERVAL_RANGE.last.toFloat(),
                    steps = 6, // 15, 30, … 120
                    onValueChangeFinished = { settingsVm.setInterval(interval.toInt()) },
                )
                var retention by remember(settings.retentionDays) { mutableFloatStateOf(settings.retentionDays.toFloat()) }
                Text(stringResource(R.string.set_retention, retention.toInt()))
                Slider(
                    value = retention,
                    onValueChange = { retention = it },
                    valueRange = 30f..730f,
                    onValueChangeFinished = { vm.updateSettings { it.copy(retentionDays = retention.toInt()) } },
                )
            }
        }
        item {
            SectionCard(stringResource(R.string.set_section_energy)) {
                NumberField(stringResource(R.string.set_gas_factor), settings.gasKwhPerCubicMeter) { v -> vm.updateSettings { it.copy(gasKwhPerCubicMeter = v) } }
            }
        }
        item {
            SectionCard(stringResource(R.string.set_notifications)) {
                SwitchRow(stringResource(R.string.set_notifications), settings.notificationsEnabled) { v -> vm.updateSettings { it.copy(notificationsEnabled = v) } }
                var offline by remember(settings.offlineAlertMinutes) { mutableFloatStateOf(settings.offlineAlertMinutes.toFloat()) }
                Text(stringResource(R.string.set_offline, offline.toInt()))
                Slider(offline, { offline = it }, valueRange = 15f..240f, onValueChangeFinished = { vm.updateSettings { it.copy(offlineAlertMinutes = offline.toInt()) } })
                Text(stringResource(R.string.set_pressure, formatNumber(settings.pressureMinBar), formatNumber(settings.pressureMaxBar)))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("min bar", settings.pressureMinBar, Modifier.weight(1f)) { v -> vm.updateSettings { it.copy(pressureMinBar = v) } }
                    NumberField("max bar", settings.pressureMaxBar, Modifier.weight(1f)) { v -> vm.updateSettings { it.copy(pressureMaxBar = v) } }
                }
            }
        }
        item {
            SectionCard(stringResource(R.string.set_section_data)) {
                OutlinedButton(onClick = settingsVm::exportCsv, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.set_export_csv)) }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

/** Decimal input that is saved only when the user taps Save and the value parses. */
@Composable
private fun NumberField(label: String, value: Double, modifier: Modifier = Modifier, onSave: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    Column(modifier) {
        OutlinedTextField(
            text, { text = it },
            label = { Text(label) },
            singleLine = true,
            isError = parsed == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        if (parsed != null && parsed != value) TextButton(onClick = { onSave(parsed) }) { Text(stringResource(R.string.set_save)) }
    }
}

private fun share(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.set_export_csv)))
}
