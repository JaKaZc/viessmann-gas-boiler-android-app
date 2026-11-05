package com.vigasboiler.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.vigasboiler.app.analytics.Analytics
import com.vigasboiler.app.auth.TokenManager
import com.vigasboiler.app.history.HistoryBackup
import com.vigasboiler.app.widget.BoilerWidget
import com.vigasboiler.app.settings.SettingsRepository
import com.vigasboiler.app.work.BackgroundRefresh

@HiltAndroidApp
class ViessmannApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var analytics: Analytics
    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var historyBackup: HistoryBackup

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Keeps the periodic refresh registered (UPDATE policy keeps its timing). The worker
        // does nothing while signed out.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            BackgroundRefresh.schedule(this@ViessmannApp, settings.current().backgroundIntervalMinutes)
        }
        // After a reinstall or on a new phone: the history Android's backup brought back.
        scope.launch { historyBackup.restore() }
        // Widgets switch between "sign in" and data, and show the next refresh for a new interval.
        scope.launch {
            combine(tokenManager.state, settings.settings.map { it.backgroundIntervalMinutes }) { auth, interval -> auth to interval }
                .distinctUntilChanged()
                .drop(1) // the first value is the state at app start, already shown
                .collect { BoilerWidget.redraw(this@ViessmannApp) }
        }
        // Analytics collects only while the user's answer is yes.
        scope.launch {
            settings.settings.map { it.analyticsConsent == true }.distinctUntilChanged().collect(analytics::setCollectionEnabled)
        }
    }
}
