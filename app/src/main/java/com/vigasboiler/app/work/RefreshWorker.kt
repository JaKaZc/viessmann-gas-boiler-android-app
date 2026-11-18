package com.vigasboiler.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.vigasboiler.app.auth.AuthState
import com.vigasboiler.app.auth.TokenManager
import com.vigasboiler.app.feature.FeatureRepository
import com.vigasboiler.app.feature.RefreshOutcome
import com.vigasboiler.app.ratelimit.CallKind
import com.vigasboiler.app.ratelimit.RateLimitTracker
import com.vigasboiler.app.widget.BoilerWidget
import com.vigasboiler.app.widget.WidgetProblem

/**
 * Periodic background refresh: one full feature fetch (2 calls) that is stored as a history
 * snapshot and checked for notifications. It never sends commands.
 */
@HiltWorker
class RefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: FeatureRepository,
    private val tokenManager: TokenManager,
    private val tracker: RateLimitTracker,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        tokenManager.initialize()
        if (tokenManager.state.value != AuthState.SignedIn) return Result.success()
        when (val outcome = repository.refresh(CallKind.Background)) {
            is RefreshOutcome.Failed -> if (outcome.httpCode == null || outcome.httpCode >= 500) notifier.onUnreachable()
            else -> Unit // success is handled by the snapshot listeners; budget/too-soon just skip
        }
        notifier.onBudget(tracker.currentBudget())
        BoilerWidget.redraw(applicationContext)
        return Result.success() // never retry: retries would spend budget outside the schedule
    }
}

/**
 * The widget button's refresh again, after it found no network: runs once there is a connection.
 * Android reports a connection a moment before it works, so it tries a few more times.
 */
@HiltWorker
class WidgetRetryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: FeatureRepository,
    private val tokenManager: TokenManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        tokenManager.initialize()
        if (tokenManager.state.value != AuthState.SignedIn) return Result.success()
        val outcome = repository.refresh(CallKind.Manual)
        if (WidgetProblem.of(outcome) == WidgetProblem.NoConnection && runAttemptCount < RETRIES) return Result.retry()
        BoilerWidget.redraw(applicationContext)
        return Result.success()
    }

    private companion object {
        const val RETRIES = 3
    }
}

object BackgroundRefresh {
    private const val WORK_NAME = "background-refresh"
    private const val RETRY_WORK_NAME = "widget-retry"

    fun schedule(context: Context, intervalMinutes: Int) {
        val minutes = intervalMinutes.toLong().coerceIn(15, 120)
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(minutes, TimeUnit.MINUTES)
            // Periodic work runs at once when first enqueued, which would duplicate the
            // foreground refresh of the app start and waste two calls.
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun retryWhenOnline(context: Context) {
        val request = OneTimeWorkRequestBuilder<WidgetRetryWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(RETRY_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** When WorkManager plans the next run; null while it runs or when nothing is scheduled. */
    fun nextRun(context: Context): Flow<Long?> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
            infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }?.nextScheduleTimeMillis?.takeIf { it != Long.MAX_VALUE }
        }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
