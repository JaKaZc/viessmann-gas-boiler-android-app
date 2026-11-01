package si.vicare.customapp

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import si.vicare.customapp.settings.SettingsRepository
import si.vicare.customapp.work.BackgroundRefresh

@HiltAndroidApp
class ViessmannApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settings: SettingsRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Keeps the periodic refresh registered (UPDATE policy keeps its timing). The worker
        // does nothing while signed out.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            BackgroundRefresh.schedule(this@ViessmannApp, settings.current().backgroundIntervalMinutes)
        }
    }
}
