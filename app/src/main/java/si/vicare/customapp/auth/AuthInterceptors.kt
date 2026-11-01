package si.vicare.customapp.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

private const val AUTHORIZATION = "Authorization"
private const val BEARER = "Bearer "

/** Adds the bearer token, refreshing it first when it is about to expire. */
class AuthInterceptor(private val tokenManager: TokenManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { tokenManager.validAccessToken() }
        val request = chain.request().newBuilder().apply {
            if (token != null) header(AUTHORIZATION, BEARER + token)
        }.build()
        return chain.proceed(request)
    }
}

/**
 * Handles an unexpected 401 by refreshing once and retrying. Refreshes are serialized inside
 * [TokenManager]; a request that raced with another refresh just picks up the new token.
 */
class TokenAuthenticator(private val tokenManager: TokenManager) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponseCount() >= 1) return null // already retried once, give up
        val failedToken = response.request.header(AUTHORIZATION)?.removePrefix(BEARER)
        val newToken = runBlocking { tokenManager.refreshAfterUnauthorized(failedToken) } ?: return null
        return response.request.newBuilder().header(AUTHORIZATION, BEARER + newToken).build()
    }

    private fun Response.priorResponseCount(): Int {
        var count = 0
        var prior = priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
