package si.vicare.customapp.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import si.vicare.customapp.auth.AuthState
import si.vicare.customapp.auth.TokenManager
import si.vicare.customapp.feature.FeatureRepository
import si.vicare.customapp.feature.RefreshOutcome
import si.vicare.customapp.ratelimit.CallKind
import si.vicare.customapp.ratelimit.RateLimitTracker

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
        return Result.success() // never retry: retries would spend budget outside the schedule
    }
}

object BackgroundRefresh {
    private const val WORK_NAME = "background-refresh"

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

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
