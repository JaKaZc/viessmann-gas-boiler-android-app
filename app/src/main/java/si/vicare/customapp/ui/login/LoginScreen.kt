package si.vicare.customapp.ui.login

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import si.vicare.customapp.ui.common.IconBadge
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import si.vicare.customapp.R

@Composable
fun LoginScreen(viewModel: LoginViewModel, sessionExpired: Boolean) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onAuthorizationResult(result.data)
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconBadge(R.drawable.ic_nav_heating, MaterialTheme.colorScheme.primary, size = 88)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium)
            Text(
                stringResource(R.string.login_intro),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (sessionExpired) {
                Text(stringResource(R.string.login_session_expired), color = MaterialTheme.colorScheme.error)
            }
            if (!ui.clientConfigured) {
                Text(
                    stringResource(R.string.login_client_missing),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            if (ui.cancelled) {
                Text(stringResource(R.string.login_cancelled), textAlign = TextAlign.Center)
            }
            ui.error?.let {
                Text(stringResource(R.string.login_failed, it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            if (ui.inProgress) {
                CircularProgressIndicator()
            } else {
                Button(
                    enabled = ui.clientConfigured,
                    onClick = { launcher.launch(viewModel.authorizationIntent()) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.login_button), style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}
