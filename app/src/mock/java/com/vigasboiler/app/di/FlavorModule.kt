package com.vigasboiler.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient
import com.vigasboiler.app.analytics.Analytics
import com.vigasboiler.app.analytics.NoAnalytics
import com.vigasboiler.app.auth.RefreshResult
import com.vigasboiler.app.auth.TokenManager
import com.vigasboiler.app.auth.TokenStorage
import com.vigasboiler.app.auth.Tokens
import com.vigasboiler.app.mock.MockApiInterceptor
import com.vigasboiler.app.ratelimit.RateLimitInterceptor
import com.vigasboiler.app.ratelimit.RateLimitTracker

/** `mock` flavor: no login, every API call is answered from the redacted dump in assets. */
@Module
@InstallIn(SingletonComponent::class)
object FlavorModule {

    @Provides @Singleton
    fun tokenManager(): TokenManager = TokenManager(
        storage = object : TokenStorage {
            private var tokens: Tokens? = Tokens("mock-access", "mock-refresh", Long.MAX_VALUE)
            override suspend fun read() = tokens
            override suspend fun write(tokens: Tokens) { this.tokens = tokens }
            override suspend fun clear() { tokens = null }
        },
        refreshClient = { RefreshResult.Success("mock-access", null, 3600) },
    )

    @Provides @Singleton
    fun analytics(): Analytics = NoAnalytics

    @Provides @Singleton
    fun apiHttpClient(@ApplicationContext context: Context, tracker: RateLimitTracker): OkHttpClient =
        OkHttpClient.Builder()
            // Counted like real calls so the budget UI can be developed against the mock.
            .addInterceptor(RateLimitInterceptor(tracker))
            .addInterceptor(MockApiInterceptor(context.assets))
            .build()
}
