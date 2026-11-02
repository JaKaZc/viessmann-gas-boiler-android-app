package com.vigasboiler.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient
import com.vigasboiler.app.BuildConfig
import com.vigasboiler.app.analytics.Analytics
import com.vigasboiler.app.analytics.FirebaseAnalyticsTracker
import com.vigasboiler.app.auth.AuthInterceptor
import com.vigasboiler.app.auth.ClientIdStore
import com.vigasboiler.app.auth.EncryptedTokenStorage
import com.vigasboiler.app.auth.IamRefreshClient
import com.vigasboiler.app.auth.TokenAuthenticator
import com.vigasboiler.app.auth.TokenManager
import com.vigasboiler.app.config.ApiConfig
import com.vigasboiler.app.net.RedactingLogInterceptor
import com.vigasboiler.app.ratelimit.RateLimitInterceptor
import com.vigasboiler.app.ratelimit.RateLimitTracker

/** `live` flavor: real IAM login and the real Viessmann API. */
@Module
@InstallIn(SingletonComponent::class)
object FlavorModule {

    @Provides @Singleton
    fun tokenManager(@ApplicationContext context: Context, config: ApiConfig, clientIdStore: ClientIdStore): TokenManager {
        val iamClient = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .apply { if (BuildConfig.DEBUG) addInterceptor(RedactingLogInterceptor()) }
            .build()
        return TokenManager(
            storage = EncryptedTokenStorage(context),
            refreshClient = IamRefreshClient(iamClient, config.tokenEndpoint) { clientIdStore.read() },
        )
    }

    @Provides @Singleton
    fun analytics(@ApplicationContext context: Context): Analytics = FirebaseAnalyticsTracker(context)

    @Provides @Singleton
    fun apiHttpClient(tokenManager: TokenManager, tracker: RateLimitTracker): OkHttpClient =
        OkHttpClient.Builder()
            .callTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenManager))
            .authenticator(TokenAuthenticator(tokenManager))
            // Network interceptor: also counts the retry after a 401 refresh.
            .addNetworkInterceptor(RateLimitInterceptor(tracker))
            .apply { if (BuildConfig.DEBUG) addInterceptor(RedactingLogInterceptor()) }
            .build()
}
