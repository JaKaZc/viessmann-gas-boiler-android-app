package com.vigasboiler.app.di

import android.content.Context
import androidx.browser.customtabs.CustomTabsClient
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import java.io.File
import javax.inject.Singleton
import net.openid.appauth.AuthorizationService
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import android.util.Log
import com.vigasboiler.app.BuildConfig
import com.vigasboiler.app.analysis.HeatingCheckRepository
import com.vigasboiler.app.auth.AuthRepository
import com.vigasboiler.app.auth.ClientIdStore
import com.vigasboiler.app.auth.DataStoreClientIdStore
import com.vigasboiler.app.auth.TokenManager
import com.vigasboiler.app.config.ApiConfig
import com.vigasboiler.app.data.AppDatabase
import com.vigasboiler.app.data.RoomApiCallLog
import com.vigasboiler.app.demo.DemoSession
import com.vigasboiler.app.discovery.DiscoveryRepository
import com.vigasboiler.app.discovery.DumpExporter
import com.vigasboiler.app.feature.DataStoreSelectionStore
import com.vigasboiler.app.feature.FeatureRepository
import com.vigasboiler.app.feature.SelectionStore
import com.vigasboiler.app.feature.SnapshotListener
import com.vigasboiler.app.history.HistoryBackup
import com.vigasboiler.app.history.HistoryRepository
import com.vigasboiler.app.messages.ErrorLookupRepository
import com.vigasboiler.app.net.ViessmannApi
import com.vigasboiler.app.ratelimit.DataStoreBlockStore
import com.vigasboiler.app.ratelimit.RateLimitTracker
import com.vigasboiler.app.settings.SettingsRepository
import com.vigasboiler.app.widget.BoilerWidget
import com.vigasboiler.app.work.Notifier

/** Bindings shared by both flavors. The API OkHttpClient and TokenManager come from FlavorModule. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun apiConfig(): ApiConfig = ApiConfig.Default

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "viessmann.db").build()

    @Provides @Singleton
    fun rateLimitTracker(@ApplicationContext context: Context, db: AppDatabase): RateLimitTracker =
        RateLimitTracker(RoomApiCallLog(db.apiCallDao()), DataStoreBlockStore(context))

    @Provides @Singleton
    fun settingsRepository(@ApplicationContext context: Context): SettingsRepository = SettingsRepository(context)

    @Provides @Singleton
    fun selectionStore(@ApplicationContext context: Context): SelectionStore = DataStoreSelectionStore(context)

    @Provides @Singleton
    fun clientIdStore(@ApplicationContext context: Context): ClientIdStore = DataStoreClientIdStore(context)

    @Provides @Singleton
    fun authorizationService(@ApplicationContext context: Context): AuthorizationService = AuthorizationService(context)

    @Provides @Singleton
    fun authRepository(
        @ApplicationContext context: Context,
        config: ApiConfig,
        service: AuthorizationService,
        tokenManager: TokenManager,
    ): AuthRepository = AuthRepository(config, service, tokenManager) {
        // The browser that would open the login page: the default one if it supports Custom Tabs.
        val browser = CustomTabsClient.getPackageName(context, null)
        browser != null && CustomTabsClient.isAuthTabSupported(context, browser)
    }

    @Provides @Singleton
    fun viessmannApi(config: ApiConfig, client: OkHttpClient): ViessmannApi =
        Retrofit.Builder()
            .baseUrl(config.apiBaseUrl.trimEnd('/') + "/")
            .client(client)
            .build()
            .create(ViessmannApi::class.java)

    @Provides @Singleton
    fun featureRepository(
        @ApplicationContext context: Context,
        config: ApiConfig,
        api: ViessmannApi,
        tracker: RateLimitTracker,
        selectionStore: SelectionStore,
        listeners: Set<@JvmSuppressWildcards SnapshotListener>,
    ): FeatureRepository = FeatureRepository(
        api = api,
        tracker = tracker,
        selectionStore = selectionStore,
        apiBaseUrl = config.apiBaseUrl,
        cacheDir = File(context.filesDir, "snapshot"),
        discoveryDir = File(context.filesDir, "debug-dump"),
        listeners = listeners.toList(),
    )

    @Provides @Singleton
    fun errorLookupRepository(
        @ApplicationContext context: Context,
        api: ViessmannApi,
        tracker: RateLimitTracker,
        selectionStore: SelectionStore,
    ): ErrorLookupRepository {
        return ErrorLookupRepository(
            api, tracker, selectionStore,
            discoveryDir = File(context.filesDir, "debug-dump"),
            cacheDir = File(context.cacheDir, "error-database"),
            log = { if (BuildConfig.DEBUG) Log.d("ErrorLookup", it) },
        )
    }

    @Provides @Singleton
    fun discoveryRepository(@ApplicationContext context: Context, api: ViessmannApi): DiscoveryRepository =
        DiscoveryRepository(api, File(context.filesDir, "debug-dump"))

    @Provides @Singleton
    fun demoSession(
        @ApplicationContext context: Context,
        tokenManager: TokenManager,
        features: FeatureRepository,
        history: HistoryRepository,
        backup: HistoryBackup,
        settings: SettingsRepository,
    ): DemoSession = DemoSession(
        context, tokenManager, features, history, backup, settings,
        sampleDirs = listOf(File(context.filesDir, "debug-dump"), File(context.cacheDir, "error-database")),
    )

    @Provides @Singleton
    fun dumpExporter(@ApplicationContext context: Context): DumpExporter =
        DumpExporter(File(context.filesDir, "debug-dump"), File(context.cacheDir, "exports"))
}

@Module
@InstallIn(SingletonComponent::class)
object HistoryModule {
    @Provides @Singleton
    fun historyRepository(db: AppDatabase, settings: SettingsRepository): HistoryRepository =
        HistoryRepository(db.snapshotDao(), settings)

    @Provides @Singleton
    fun historyBackup(@ApplicationContext context: Context, history: HistoryRepository, settings: SettingsRepository, tokenManager: TokenManager): HistoryBackup =
        HistoryBackup(
            dir = File(context.filesDir, "history-backup"),
            restoredMarker = File(context.noBackupFilesDir, "history-restored"),
            store = history,
            retentionDays = { settings.current().retentionDays },
            isDemo = tokenManager::isDemo,
        )

    @Provides @IntoSet
    fun historyBackupListener(backup: HistoryBackup): SnapshotListener = backup

    /** Every successful refresh (foreground or background) is stored as a history snapshot. */
    @Provides @IntoSet
    fun historyListener(history: HistoryRepository): SnapshotListener = history

    @Provides @Singleton
    fun heatingCheckRepository(history: HistoryRepository, features: FeatureRepository, settings: SettingsRepository): HeatingCheckRepository =
        HeatingCheckRepository(history, features, settings)

    @Provides @Singleton
    fun notifier(@ApplicationContext context: Context, settings: SettingsRepository): Notifier = Notifier(context, settings)

    @Provides @IntoSet
    fun notifierListener(notifier: Notifier): SnapshotListener = notifier

    /** Home-screen widgets show every new snapshot, from the app or the background refresh. */
    @Provides @IntoSet
    fun widgetListener(@ApplicationContext context: Context): SnapshotListener =
        SnapshotListener { _, _ -> BoilerWidget.redraw(context) }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MultibindingModule {
    /** History recording and notifications contribute listeners with @IntoSet. */
    @Multibinds
    abstract fun snapshotListeners(): Set<SnapshotListener>
}
