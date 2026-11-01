package si.vicare.customapp.di

import android.content.Context
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
import si.vicare.customapp.BuildConfig
import si.vicare.customapp.auth.AuthRepository
import si.vicare.customapp.auth.TokenManager
import si.vicare.customapp.config.ApiConfig
import si.vicare.customapp.data.AppDatabase
import si.vicare.customapp.data.RoomApiCallLog
import si.vicare.customapp.discovery.DiscoveryRepository
import si.vicare.customapp.discovery.DumpExporter
import si.vicare.customapp.feature.DataStoreSelectionStore
import si.vicare.customapp.feature.FeatureRepository
import si.vicare.customapp.feature.SelectionStore
import si.vicare.customapp.feature.SnapshotListener
import si.vicare.customapp.history.HistoryRepository
import si.vicare.customapp.messages.ErrorLookupRepository
import si.vicare.customapp.net.ViessmannApi
import si.vicare.customapp.ratelimit.DataStoreBlockStore
import si.vicare.customapp.ratelimit.RateLimitTracker
import si.vicare.customapp.settings.SettingsRepository
import si.vicare.customapp.work.Notifier

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
    fun authorizationService(@ApplicationContext context: Context): AuthorizationService = AuthorizationService(context)

    @Provides @Singleton
    fun authRepository(config: ApiConfig, service: AuthorizationService, tokenManager: TokenManager): AuthRepository =
        AuthRepository(config, service, tokenManager)

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
    fun dumpExporter(@ApplicationContext context: Context): DumpExporter =
        DumpExporter(File(context.filesDir, "debug-dump"), File(context.cacheDir, "exports"))
}

@Module
@InstallIn(SingletonComponent::class)
object HistoryModule {
    @Provides @Singleton
    fun historyRepository(db: AppDatabase, settings: SettingsRepository): HistoryRepository =
        HistoryRepository(db.snapshotDao(), settings)

    /** Every successful refresh (foreground or background) is stored as a history snapshot. */
    @Provides @IntoSet
    fun historyListener(history: HistoryRepository): SnapshotListener = history

    @Provides @Singleton
    fun notifier(@ApplicationContext context: Context, settings: SettingsRepository): Notifier = Notifier(context, settings)

    @Provides @IntoSet
    fun notifierListener(notifier: Notifier): SnapshotListener = notifier
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MultibindingModule {
    /** History recording and notifications contribute listeners with @IntoSet. */
    @Multibinds
    abstract fun snapshotListeners(): Set<SnapshotListener>
}
