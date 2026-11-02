package com.vigasboiler.app

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import javax.inject.Inject
import kotlinx.coroutines.flow.map
import com.vigasboiler.app.settings.SettingsRepository
import com.vigasboiler.app.settings.ThemeMode
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
import com.vigasboiler.app.auth.AuthState
import com.vigasboiler.app.ui.AppShell
import com.vigasboiler.app.ui.AppViewModel
import com.vigasboiler.app.ui.login.SetupWizard
import com.vigasboiler.app.ui.settings.AnalyticsConsentDialog
import com.vigasboiler.app.ui.login.LoginViewModel
import com.vigasboiler.app.ui.theme.ViessmannTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by remember { settings.settings.map { it.themeMode } }.collectAsStateWithLifecycle(ThemeMode.System)
            val dark = when (themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // Status and navigation bar icons follow the app's theme, not the phone's.
            DisposableEffect(dark) {
                val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose { }
            }
            ViessmannTheme(darkTheme = dark) {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot(loginViewModel: LoginViewModel = hiltViewModel()) {
    val authState by loginViewModel.authState.collectAsStateWithLifecycle()
    val client by loginViewModel.client.collectAsStateWithLifecycle()
    when (authState) {
        AuthState.Unknown -> Loading()
        AuthState.SignedIn -> {
            val appViewModel = hiltViewModel<AppViewModel>()
            val askConsent by appViewModel.askAnalyticsConsent.collectAsStateWithLifecycle()
            RequestNotificationPermission()
            AppShell(vm = appViewModel, onSignOut = loginViewModel::signOut)
            // Asked once, after the first sign-in; the answer closes the dialog for good.
            if (askConsent) AnalyticsConsentDialog(onAnswer = appViewModel::setAnalyticsConsent)
        }
        // The wizard picks its first page from the stored client ID, so wait until it is read.
        AuthState.SignedOut, AuthState.SessionExpired -> if (!client.loaded) Loading() else SetupWizard(
            viewModel = loginViewModel,
            sessionExpired = authState == AuthState.SessionExpired,
        )
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** Asks once for POST_NOTIFICATIONS (Android 13+); alerts are skipped silently without it. */
@Composable
private fun RequestNotificationPermission() {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
