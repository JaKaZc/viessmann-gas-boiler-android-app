package com.vigasboiler.app.auth

import kotlinx.serialization.Serializable

@Serializable
data class Tokens(
    val accessToken: String,
    val refreshToken: String,
    /** Epoch millis after which the access token must be treated as expired. */
    val expiresAtMillis: Long,
) {
    fun expiresWithin(nowMillis: Long, marginMillis: Long): Boolean =
        nowMillis + marginMillis >= expiresAtMillis

    /** Demo mode: placeholder tokens that never expire and never reach a server. */
    val isDemo: Boolean get() = accessToken == DEMO.accessToken

    companion object {
        val DEMO = Tokens("demo", "demo", Long.MAX_VALUE)
    }

    // Never print token values, not even by accident through string templates.
    override fun toString(): String = "Tokens(accessToken=***, refreshToken=***, expiresAtMillis=$expiresAtMillis)"
}

/** Persistence for [Tokens]. The production implementation encrypts with an Android Keystore key. */
interface TokenStorage {
    suspend fun read(): Tokens?
    suspend fun write(tokens: Tokens)
    suspend fun clear()
}

/** Outcome of a refresh-token grant. */
sealed interface RefreshResult {
    data class Success(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long) : RefreshResult

    /** The IAM rejected the refresh token (`invalid_grant` or similar). The user has to log in again. */
    data class Rejected(val error: String) : RefreshResult

    /** Network failure or 5xx. Tokens stay as they are; the request simply fails. */
    data class TransientFailure(val cause: Throwable?) : RefreshResult
}

fun interface RefreshClient {
    suspend fun refresh(refreshToken: String): RefreshResult
}
