package com.vigasboiler.app.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AuthState { Unknown, SignedOut, SignedIn, SessionExpired }

/**
 * Owns the current tokens and serializes refreshes: however many requests hit a 401 or an
 * expired token at the same time, only one refresh-token grant is sent.
 */
class TokenManager(
    private val storage: TokenStorage,
    private val refreshClient: RefreshClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var cached: Tokens? = null
    private var loaded = false

    private val _state = MutableStateFlow(AuthState.Unknown)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _demo = MutableStateFlow(false)
    /** Signed in with [Tokens.DEMO]: the app shows the sample installation. */
    val demo: StateFlow<Boolean> = _demo.asStateFlow()

    suspend fun initialize() {
        mutex.withLock { load() }
    }

    suspend fun onSignedIn(tokens: Tokens) = mutex.withLock {
        storage.write(tokens)
        cached = tokens
        loaded = true
        _demo.value = tokens.isDemo
        _state.value = AuthState.SignedIn
    }

    /** Whether API calls must be answered from the sample data; reads storage on first use. */
    suspend fun isDemo(): Boolean = mutex.withLock { load()?.isDemo == true }

    suspend fun signOut() = mutex.withLock {
        clearLocked(AuthState.SignedOut)
    }

    /**
     * Returns an access token that is not about to expire, refreshing proactively when needed.
     * Proactive refresh avoids spending a rate-limited API call on a guaranteed 401.
     */
    suspend fun validAccessToken(): String? = mutex.withLock {
        val tokens = load() ?: return@withLock null
        if (!tokens.expiresWithin(clock(), EXPIRY_MARGIN_MILLIS)) return@withLock tokens.accessToken
        refreshLocked(tokens)?.accessToken
    }

    /**
     * Called after the API answered 401 for a request sent with [failedAccessToken].
     * If another caller already refreshed in the meantime the new token is returned without a
     * second refresh. Returns null when no usable token can be obtained.
     */
    suspend fun refreshAfterUnauthorized(failedAccessToken: String?): String? = mutex.withLock {
        val tokens = load() ?: return@withLock null
        if (failedAccessToken != null && tokens.accessToken != failedAccessToken) {
            return@withLock tokens.accessToken
        }
        refreshLocked(tokens)?.accessToken
    }

    private suspend fun load(): Tokens? {
        if (!loaded) {
            cached = storage.read()
            loaded = true
            _demo.value = cached?.isDemo == true
            if (_state.value == AuthState.Unknown) {
                _state.value = if (cached != null) AuthState.SignedIn else AuthState.SignedOut
            }
        }
        return cached
    }

    private suspend fun refreshLocked(current: Tokens): Tokens? =
        when (val result = refreshClient.refresh(current.refreshToken)) {
            is RefreshResult.Success -> {
                val updated = Tokens(
                    accessToken = result.accessToken,
                    // The IAM may or may not rotate the refresh token; keep the old one if none is returned.
                    refreshToken = result.refreshToken ?: current.refreshToken,
                    expiresAtMillis = clock() + result.expiresInSeconds * 1000,
                )
                storage.write(updated)
                cached = updated
                updated
            }
            is RefreshResult.Rejected -> {
                clearLocked(AuthState.SessionExpired)
                null
            }
            is RefreshResult.TransientFailure -> null
        }

    private suspend fun clearLocked(newState: AuthState) {
        storage.clear()
        cached = null
        loaded = true
        _demo.value = false
        _state.value = newState
    }

    companion object {
        const val EXPIRY_MARGIN_MILLIS = 60_000L
    }
}
