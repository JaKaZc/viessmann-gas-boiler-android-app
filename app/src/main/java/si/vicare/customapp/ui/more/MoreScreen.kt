package si.vicare.customapp.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import si.vicare.customapp.R
import si.vicare.customapp.ui.Navigate
import si.vicare.customapp.ui.common.NavRow
import si.vicare.customapp.ui.common.RowDivider
import si.vicare.customapp.ui.common.SectionHeader

@Composable
fun MoreScreen(navigate: Navigate, onSignOut: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader(stringResource(R.string.more_section_boiler))
        Group {
            NavRow(stringResource(R.string.more_device), R.drawable.ic_boiler, stringResource(R.string.more_device_sub)) { navigate("device") }
            RowDivider()
            NavRow(stringResource(R.string.msg_info_title), R.drawable.ic_info, stringResource(R.string.more_status_sub)) { navigate("status-messages") }
            RowDivider()
            NavRow(stringResource(R.string.more_history), R.drawable.ic_clock, stringResource(R.string.more_history_sub)) { navigate("history") }
            RowDivider()
            NavRow(stringResource(R.string.more_explorer), R.drawable.ic_list, stringResource(R.string.more_explorer_sub)) { navigate("explorer") }
        }
        SectionHeader(stringResource(R.string.more_section_app))
        Group {
            NavRow(stringResource(R.string.more_settings), R.drawable.ic_tune, stringResource(R.string.more_settings_sub)) { navigate("settings") }
            RowDivider()
            NavRow(stringResource(R.string.more_discovery), R.drawable.ic_bug, stringResource(R.string.more_discovery_sub)) { navigate("discovery") }
            RowDivider()
            NavRow(stringResource(R.string.set_sign_out), R.drawable.ic_logout, null, onSignOut)
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = MaterialTheme.shapes.medium) {
        Column { content() }
    }
}
