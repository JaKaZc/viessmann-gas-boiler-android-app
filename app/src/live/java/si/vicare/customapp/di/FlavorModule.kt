package si.vicare.customapp.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient
import si.vicare.customapp.BuildConfig
import si.vicare.customapp.auth.AuthInterceptor
import si.vicare.customapp.auth.EncryptedTokenStorage
import si.vicare.customapp.auth.IamRefreshClient
import si.vicare.customapp.auth.TokenAuthenticator
import si.vicare.customapp.auth.TokenManager
import si.vicare.customapp.config.ApiConfig
import si.vicare.customapp.net.RedactingLogInterceptor
import si.vicare.customapp.ratelimit.RateLimitInterceptor
import si.vicare.customapp.ratelimit.RateLimitTracker

/** `live` flavor: real IAM login and the real Viessmann API. */
@Module
@InstallIn(SingletonComponent::class)
object FlavorModule {

    @Provides @Singleton
    fun tokenManager(@ApplicationContext context: Context, config: ApiConfig): TokenManager {
        val iamClient = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .apply { if (BuildConfig.DEBUG) addInterceptor(RedactingLogInterceptor()) }
            .build()
        return TokenManager(
            storage = EncryptedTokenStorage(context),
            refreshClient = IamRefreshClient(iamClient, config.tokenEndpoint, config.clientId),
        )
    }

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
