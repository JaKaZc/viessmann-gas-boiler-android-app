package com.vigasboiler.app.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenManagerTest {

    private val now = 1_000_000L
    private fun validTokens() = Tokens("old", "refresh-1", expiresAtMillis = now + 3_600_000)

    @Test
    fun `initialize reports SignedOut without stored tokens and SignedIn with them`() = runTest {
        val empty = TokenManager(FakeTokenStorage(), CountingRefreshClient()) { now }
        empty.initialize()
        assertEquals(AuthState.SignedOut, empty.state.value)

        val stored = TokenManager(FakeTokenStorage(validTokens()), CountingRefreshClient()) { now }
        stored.initialize()
        assertEquals(AuthState.SignedIn, stored.state.value)
    }

    @Test
    fun `demo tokens switch demo mode on until sign-out, and survive a restart`() = runTest {
        val storage = FakeTokenStorage()
        val manager = TokenManager(storage, CountingRefreshClient()) { now }
        manager.onSignedIn(Tokens.DEMO)
        assertEquals(true, manager.demo.value)
        assertEquals(true, manager.isDemo())
        assertEquals(true, TokenManager(storage, CountingRefreshClient()) { now }.isDemo())
        manager.signOut()
        assertEquals(false, manager.demo.value)
        assertEquals(false, manager.isDemo())
        manager.onSignedIn(validTokens())
        assertEquals(false, manager.demo.value)
    }

    @Test
    fun `valid token is returned without refreshing`() = runTest {
        val refresh = CountingRefreshClient()
        val manager = TokenManager(FakeTokenStorage(validTokens()), refresh) { now }
        assertEquals("old", manager.validAccessToken())
        assertEquals(0, refresh.calls)
    }

    @Test
    fun `token about to expire is refreshed proactively and refresh token is kept when not rotated`() = runTest {
        val storage = FakeTokenStorage(Tokens("old", "refresh-1", expiresAtMillis = now + 30_000))
        val refresh = CountingRefreshClient()
        val manager = TokenManager(storage, refresh) { now }

        assertEquals("new-1", manager.validAccessToken())
        assertEquals(1, refresh.calls)
        assertEquals("refresh-1", storage.tokens?.refreshToken)
        assertEquals(now + 3_600_000, storage.tokens?.expiresAtMillis)
    }

    @Test
    fun `rotated refresh token is stored`() = runTest {
        val storage = FakeTokenStorage(validTokens())
        val manager = TokenManager(storage, CountingRefreshClient { RefreshResult.Success("a2", "refresh-2", 3600) }) { now }
        manager.refreshAfterUnauthorized("old")
        assertEquals("refresh-2", storage.tokens?.refreshToken)
    }

    @Test
    fun `401 for a token that was already replaced does not refresh again`() = runTest {
        val refresh = CountingRefreshClient()
        val manager = TokenManager(FakeTokenStorage(validTokens()), refresh) { now }

        assertEquals("new-1", manager.refreshAfterUnauthorized("old"))
        assertEquals("new-1", manager.refreshAfterUnauthorized("old")) // second caller with the stale token
        assertEquals(1, refresh.calls)
    }

    @Test
    fun `parallel 401s trigger exactly one refresh`() = runBlocking {
        val refresh = CountingRefreshClient(delayMillis = 100)
        val manager = TokenManager(FakeTokenStorage(validTokens()), refresh) { now }

        val results = (1..10).map { async(Dispatchers.IO) { manager.refreshAfterUnauthorized("old") } }.awaitAll()

        assertEquals(1, refresh.calls)
        assertEquals(List(10) { "new-1" }, results)
    }

    @Test
    fun `rejected refresh token clears storage and expires the session`() = runTest {
        val storage = FakeTokenStorage(validTokens())
        val manager = TokenManager(storage, CountingRefreshClient { RefreshResult.Rejected("invalid_grant") }) { now }
        manager.initialize()

        assertNull(manager.refreshAfterUnauthorized("old"))
        assertNull(storage.tokens)
        assertEquals(AuthState.SessionExpired, manager.state.value)
        assertNull(manager.validAccessToken())
    }

    @Test
    fun `transient refresh failure keeps the session`() = runTest {
        val storage = FakeTokenStorage(validTokens())
        val manager = TokenManager(storage, CountingRefreshClient { RefreshResult.TransientFailure(null) }) { now }
        manager.initialize()

        assertNull(manager.refreshAfterUnauthorized("old"))
        assertEquals("old", storage.tokens?.accessToken)
        assertEquals(AuthState.SignedIn, manager.state.value)
    }

    @Test
    fun `sign out clears tokens`() = runTest {
        val storage = FakeTokenStorage(validTokens())
        val manager = TokenManager(storage, CountingRefreshClient()) { now }
        manager.initialize()
        manager.signOut()
        assertNull(storage.tokens)
        assertEquals(AuthState.SignedOut, manager.state.value)
    }

    @Test
    fun `toString never exposes token values`() {
        val text = Tokens("secret-access", "secret-refresh", 1).toString()
        assert("secret" !in text)
    }
}
