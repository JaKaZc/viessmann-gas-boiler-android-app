package si.vicare.customapp

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import si.vicare.customapp.auth.AuthState
import si.vicare.customapp.settings.AppSettings
import si.vicare.customapp.settings.SettingsRepository
import si.vicare.customapp.ui.AppShell
import si.vicare.customapp.ui.AppViewModel
import si.vicare.customapp.ui.login.LoginScreen
import si.vicare.customapp.ui.login.LoginViewModel
import si.vicare.customapp.ui.theme.ViessmannTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appSettings by settings.settings.collectAsStateWithLifecycle(AppSettings())
            ViessmannTheme(dynamicColor = appSettings.dynamicColor) {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot(loginViewModel: LoginViewModel = hiltViewModel()) {
    val authState by loginViewModel.authState.collectAsStateWithLifecycle()
    when (authState) {
        AuthState.Unknown -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        AuthState.SignedIn -> {
            RequestNotificationPermission()
            AppShell(vm = hiltViewModel<AppViewModel>(), onSignOut = loginViewModel::signOut)
        }
        AuthState.SignedOut, AuthState.SessionExpired -> LoginScreen(
            viewModel = loginViewModel,
            sessionExpired = authState == AuthState.SessionExpired,
        )
    }
}

/** Asks once for POST_NOTIFICATIONS (Android 13+); alerts are skipped silently without it. */
@Composable
private fun RequestNotificationPermission() {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
