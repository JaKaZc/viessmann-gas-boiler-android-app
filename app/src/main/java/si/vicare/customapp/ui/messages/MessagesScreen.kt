package si.vicare.customapp.ui.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import si.vicare.customapp.R
import si.vicare.customapp.feature.DeviceMessage
import si.vicare.customapp.feature.DeviceMessages
import si.vicare.customapp.feature.MessageLevel
import si.vicare.customapp.feature.MessageSource
import si.vicare.customapp.messages.ErrorLookupRepository
import si.vicare.customapp.messages.LookupResult
import si.vicare.customapp.ui.AppViewModel
import si.vicare.customapp.ui.Navigate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import si.vicare.customapp.ui.common.AppCard
import si.vicare.customapp.ui.common.IconBadge
import si.vicare.customapp.ui.common.SectionHeader
import si.vicare.customapp.ui.theme.AppColors
import si.vicare.customapp.ui.common.Banner
import si.vicare.customapp.ui.common.BannerLevel
import si.vicare.customapp.ui.common.LabeledRow
import si.vicare.customapp.ui.common.SectionCard
import si.vicare.customapp.ui.common.formatDateTime
import si.vicare.customapp.ui.common.formatProperty

@HiltViewModel
class MessagesViewModel @Inject constructor(private val lookup: ErrorLookupRepository) : ViewModel() {
    /** code → result; a code present with null value is loading. */
    private val _results = MutableStateFlow<Map<String, LookupResult?>>(emptyMap())
    val results: StateFlow<Map<String, LookupResult?>> = _results.asStateFlow()

    fun lookup(code: String) {
        // Allow a retry after a failure; ignore taps while a lookup runs or after success.
        val previous = _results.value[code]
        if (_results.value.containsKey(code) && previous !is LookupResult.Failed) return
        _results.update { it + (code to null) }
        viewModelScope.launch {
            val result = lookup.lookup(code)
            _results.update { it + (code to result) }
        }
    }
}

/** Messages tab: only what may need attention (faults F., warnings A., malfunction lock). */
@Composable
fun MessagesScreen(vm: AppViewModel, navigate: Navigate, messagesVm: MessagesViewModel = hiltViewModel()) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val results by messagesVm.results.collectAsStateWithLifecycle()
    val s = snapshot ?: run {
        Text(stringResource(R.string.dash_no_data), Modifier.padding(16.dp))
        return
    }
    val all = DeviceMessages.all(s)
    val issues = all.filter { it.level != MessageLevel.Info }
    val infoCount = all.size - issues.size
    val malfunction = DeviceMessages.malfunction(s)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (malfunction) item { Banner(BannerLevel.Error, stringResource(R.string.msg_malfunction)) }
        if (issues.isEmpty() && !malfunction) {
            item { AllClearCard() }
        } else if (issues.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.msg_faults)) }
            item { MessageList(issues, results) { messagesVm.lookup(it) } }
        }
        item {
            AppCard(onClick = { navigate("status-messages") }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBadge(R.drawable.ic_info, MaterialTheme.colorScheme.tertiary)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.msg_info_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            pluralStringResource(R.plurals.msg_info_count, infoCount, infoCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Status (S.), service (P.) and information (I.) codes: for reference only. */
@Composable
fun StatusMessagesScreen(vm: AppViewModel, messagesVm: MessagesViewModel = hiltViewModel()) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val results by messagesVm.results.collectAsStateWithLifecycle()
    val s = snapshot ?: return
    val info = DeviceMessages.all(s).filter { it.level == MessageLevel.Info }
    val serviceFeatures = s.device.filter { it.isEnabled && it.name.startsWith("heating.service") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                stringResource(R.string.msg_info_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        if (info.isEmpty()) item { AppCard { Text(stringResource(R.string.msg_none), style = MaterialTheme.typography.bodyLarge) } }
        MessageSource.entries.forEach { source ->
            val list = info.filter { it.source == source }
            if (list.isNotEmpty()) {
                item { SectionHeader(stringResource(sourceLabel(source))) }
                item { MessageList(list, results) { messagesVm.lookup(it) } }
            }
        }
        if (serviceFeatures.isNotEmpty()) {
            item {
                SectionCard(stringResource(R.string.msg_service_features)) {
                    serviceFeatures.forEach { f -> f.properties.forEach { (k, v) -> LabeledRow("${f.name}.$k", formatProperty(v)) } }
                }
            }
        }
    }
}

@Composable
private fun MessageList(list: List<DeviceMessage>, results: Map<String, LookupResult?>, onLookup: (String) -> Unit) {
    AppCard {
        list.forEachIndexed { i, m ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            MessageRow(m, results[m.code], results.containsKey(m.code)) { onLookup(m.code) }
        }
    }
}

/** Shown when nothing needs attention. */
@Composable
private fun AllClearCard() {
    val ok = AppColors.extra.success
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconBadge(R.drawable.ic_check, ok, size = 48)
            Column {
                Text(stringResource(R.string.msg_no_faults), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.msg_no_faults_sub), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun sourceLabel(source: MessageSource) = when (source) {
    MessageSource.Error -> R.string.msg_errors
    MessageSource.Service -> R.string.msg_service
    MessageSource.Status -> R.string.msg_status
    MessageSource.Info -> R.string.msg_info
}

@Composable
private fun MessageRow(message: DeviceMessage, result: LookupResult?, requested: Boolean, onLookup: () -> Unit) {
    val color = when (message.level) {
        MessageLevel.Error -> MaterialTheme.colorScheme.error
        MessageLevel.Warning -> AppColors.extra.warning
        MessageLevel.Info -> MaterialTheme.colorScheme.tertiary
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(if (message.level == MessageLevel.Info) R.drawable.ic_info else R.drawable.ic_warning, color)
            Column(Modifier.weight(1f)) {
                Text(message.code, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(message.timestampMillis?.let(::formatDateTime), message.priority).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when {
                requested && result == null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                !requested -> TextButton(onClick = onLookup) { Text(stringResource(R.string.msg_lookup)) }
                result is LookupResult.Failed -> TextButton(onClick = onLookup) { Text(stringResource(R.string.msg_retry)) }
            }
        }
        when (result) {
            is LookupResult.Found -> LookupDetails(result.info)
            is LookupResult.NotFound -> Text(
                stringResource(R.string.msg_lookup_none, result.code), Modifier.padding(start = 48.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is LookupResult.Failed -> Text(
                stringResource(R.string.msg_lookup_failed, result.message), Modifier.padding(start = 48.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
            null -> Unit
        }
    }
}

/** Meaning, effect, what to do and the source, indented under the code. */
@Composable
private fun LookupDetails(info: si.vicare.customapp.messages.ErrorInfo) {
    Column(
        Modifier.fillMaxWidth().padding(start = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        info.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        info.effect?.let {
            Text(stringResource(R.string.msg_effect), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        if (info.solutions.isNotEmpty()) {
            Text(stringResource(R.string.msg_solution), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            info.solutions.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        info.source?.let { Text(stringResource(R.string.msg_source, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
