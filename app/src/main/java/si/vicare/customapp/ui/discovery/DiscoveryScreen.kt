package si.vicare.customapp.ui.discovery

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.util.Date
import si.vicare.customapp.R
import si.vicare.customapp.discovery.DumpEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(onSignOut: () -> Unit, showTopBar: Boolean = true, viewModel: DiscoveryViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val shareFile by viewModel.shareFile.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(shareFile) {
        shareFile?.let {
            shareZip(context, it)
            viewModel.onShareHandled()
        }
    }

    Scaffold(
        topBar = {
            if (showTopBar) TopAppBar(
                title = { Text(stringResource(R.string.discovery_title)) },
                actions = { TextButton(onClick = onSignOut) { Text(stringResource(R.string.sign_out)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.discovery_intro), style = MaterialTheme.typography.bodyMedium)
                    if (ui.running) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        ui.finishedAtMillis?.let {
                            Text(
                                stringResource(R.string.discovery_last_run, DateFormat.getDateFormat(context).format(Date(it)) + " " + DateFormat.getTimeFormat(context).format(Date(it))),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (ui.skipped) {
                        Text(stringResource(R.string.discovery_skipped), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !ui.running, onClick = viewModel::runDiscovery) {
                            Text(stringResource(R.string.discovery_run))
                        }
                        Button(enabled = !ui.running && !ui.exporting && ui.entries.isNotEmpty(), onClick = viewModel::export) {
                            Text(stringResource(R.string.discovery_export))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = ui.redactExport, onCheckedChange = viewModel::setRedactExport)
                        Text(stringResource(R.string.discovery_redact), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(ui.entries) { EntryCard(it) }
        }
    }
}

@Composable
private fun EntryCard(entry: DumpEntry) {
    val ok = entry.isSuccess
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ok) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(
                    R.string.discovery_entry_header,
                    entry.step,
                    entry.httpStatus?.toString() ?: "—",
                    entry.bytes,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(entry.endpoint, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            when {
                entry.httpStatus == 403 -> Text(stringResource(R.string.error_forbidden_basic_plan), style = MaterialTheme.typography.bodySmall)
                entry.httpStatus == 429 -> Text(stringResource(R.string.error_rate_limited), style = MaterialTheme.typography.bodySmall)
                entry.error != null -> Text(entry.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun shareZip(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.discovery_export)))
}
