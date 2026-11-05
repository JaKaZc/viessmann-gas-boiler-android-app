package com.vigasboiler.app.demo

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import com.vigasboiler.app.auth.TokenManager

/**
 * First interceptor of the live API client: in demo mode every call is answered by [sample]
 * (the bundled sample installation) and never reaches the auth, rate-limit or network layers.
 */
class DemoInterceptor(private val tokenManager: TokenManager, private val sample: Interceptor) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        if (runBlocking { tokenManager.isDemo() }) sample.intercept(chain) else chain.proceed(chain.request())
}
