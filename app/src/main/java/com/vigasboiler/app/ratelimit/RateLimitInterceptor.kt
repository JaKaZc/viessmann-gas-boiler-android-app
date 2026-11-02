package com.vigasboiler.app.ratelimit

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Counts every API request and enforces a 429 block: while blocked, requests fail locally with
 * [RateLimitedException] and never reach the network.
 */
class RateLimitInterceptor(private val tracker: RateLimitTracker) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        runBlocking { tracker.activeBlock() }?.let { throw RateLimitedException(it) }
        runBlocking { tracker.onCall() }
        val response = chain.proceed(chain.request())
        if (response.code == 429) {
            val body = runCatching { response.peekBody(MAX_ERROR_BODY).string() }.getOrNull()
            runBlocking { tracker.onRateLimited(body) }
        }
        return response
    }

    private companion object {
        const val MAX_ERROR_BODY = 64L * 1024
    }
}
