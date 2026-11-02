package com.vigasboiler.app.ui.explorer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vigasboiler.app.R
import com.vigasboiler.app.feature.Feature
import com.vigasboiler.app.feature.isDhw
import com.vigasboiler.app.ui.AppViewModel
import com.vigasboiler.app.ui.Navigate
import com.vigasboiler.app.ui.common.formatProperty

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerScreen(vm: AppViewModel, navigate: Navigate) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showDisabled by rememberSaveable { mutableStateOf(false) }
    val all = snapshot?.let { it.device + it.gateway }.orEmpty()
    val visible = all.filter { f ->
        (showDisabled || f.isEnabled) &&
            (settings.showDhw || !f.isDhw) &&
            (query.isBlank() || f.name.contains(query.trim(), ignoreCase = true))
    }.sortedBy { it.name }
    val groups = visible.groupBy { it.group }.toSortedMap()

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.exp_search)) },
                leadingIcon = { androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_search), null) },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.exp_show_disabled), Modifier.weight(1f))
                Switch(showDisabled, { showDisabled = it })
            }
            Text(stringResource(R.string.exp_count, visible.size), style = MaterialTheme.typography.labelMedium)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            groups.forEach { (group, features) ->
                stickyHeader(key = "h:$group") {
                    Text(
                        group,
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(features, key = { "f:" + (it.deviceId ?: "gw") + it.name }) { FeatureRow(it) { navigate("feature:${it.name}") } }
            }
        }
    }
}

@Composable
private fun FeatureRow(feature: Feature, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Text(feature.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (feature.commands.values.any { it.isExecutable }) Text("✎", color = MaterialTheme.colorScheme.primary)
        }
        val summary = if (!feature.isEnabled) {
            stringResource(R.string.exp_disabled)
        } else {
            feature.properties.entries.joinToString("  ") { (k, v) -> "$k=${formatProperty(v)}" }
        }
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider()
}
