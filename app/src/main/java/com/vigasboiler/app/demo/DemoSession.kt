package com.vigasboiler.app.demo

import android.content.Context
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import com.vigasboiler.app.auth.TokenManager
import com.vigasboiler.app.auth.Tokens
import com.vigasboiler.app.feature.FeatureRepository
import com.vigasboiler.app.feature.RefreshOutcome
import com.vigasboiler.app.feature.SelectionParser
import com.vigasboiler.app.history.DemoHistory
import com.vigasboiler.app.history.HistoryBackup
import com.vigasboiler.app.history.HistoryRepository
import com.vigasboiler.app.mock.MockApiInterceptor
import com.vigasboiler.app.ratelimit.CallKind
import com.vigasboiler.app.settings.SettingsRepository
import com.vigasboiler.app.widget.BoilerWidget

/**
 * Demo mode: the app without an account, on the bundled sample installation (see
 * [DemoInterceptor]) with a generated week of history. Starting and ending both wipe the device
 * data on the phone, so demo values never mix with a real boiler's; history from an earlier
 * sign-in is saved by [HistoryBackup] first and returns when the demo ends.
 */
class DemoSession(
    private val context: Context,
    private val tokenManager: TokenManager,
    private val features: FeatureRepository,
    private val history: HistoryRepository,
    private val backup: HistoryBackup,
    private val settings: SettingsRepository,
    /**
     * Files the demo can fill with sample answers: the discovery dump, and the error-database
     * cache (the sample answers 404 to lookups, which would be cached as "no entry").
     */
    private val sampleDirs: List<File>,
) {
    val active: StateFlow<Boolean> = tokenManager.demo

    suspend fun start() {
        backup.sync()
        wipe()
        val installations = withContext(Dispatchers.IO) { MockApiInterceptor.readAsset(context.assets, "02_installations.json") }
        SelectionParser.fromInstallations(installations)?.let { features.select(it) }
        tokenManager.onSignedIn(Tokens.DEMO)
        val outcome = features.refresh(CallKind.Manual)
        if (outcome is RefreshOutcome.Success) history.insert(DemoHistory.week(outcome.snapshot, ZoneId.systemDefault()))
        BoilerWidget.redraw(context)
    }

    /** Back to the setup wizard with nothing of the demo left. */
    suspend fun end() {
        backup.suspendUntilRestore()
        tokenManager.signOut()
        wipe()
        backup.restore()
        BoilerWidget.redraw(context)
    }

    private suspend fun wipe() {
        features.clear()
        history.clear()
        withContext(Dispatchers.IO) { sampleDirs.forEach { it.deleteRecursively() } }
        // The heating check offer belongs to the history that was just deleted.
        settings.update { it.copy(heatingCheckOffered = false) }
    }
}
