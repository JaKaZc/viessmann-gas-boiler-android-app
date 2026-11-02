package com.vigasboiler.app.auth

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TokenAuthenticatorTest {

    private val server = MockWebServer()
    private val now = 1_000_000L

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    /** Accepts only the given bearer token; everything else gets 401. */
    private fun acceptOnly(token: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.headers["Authorization"] == "Bearer $token") MockResponse(code = 200, body = "ok")
                else MockResponse(code = 401)
        }
    }

    private fun client(manager: TokenManager) = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(manager))
        .authenticator(TokenAuthenticator(manager))
        .build()

    private fun get(client: OkHttpClient): Int =
        client.newCall(Request.Builder().url(server.url("/features")).build()).execute().use { it.code }

    @Test
    fun `401 triggers one refresh and the request is retried with the new token`() {
        acceptOnly("new-1")
        val refresh = CountingRefreshClient()
        val manager = TokenManager(FakeTokenStorage(Tokens("old", "r", now + 3_600_000)), refresh) { now }

        assertEquals(200, get(client(manager)))
        assertEquals(1, refresh.calls)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `parallel requests hitting 401 share a single refresh`() {
        acceptOnly("new-1")
        val refresh = CountingRefreshClient(delayMillis = 200)
        val manager = TokenManager(FakeTokenStorage(Tokens("old", "r", now + 3_600_000)), refresh) { now }
        val http = client(manager)

        val pool = Executors.newFixedThreadPool(6)
        val codes = pool.invokeAll(List(6) { Callable { get(http) } }).map { it.get() }
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)

        assertEquals(List(6) { 200 }, codes)
        assertEquals(1, refresh.calls)
    }

    @Test
    fun `rejected refresh returns the 401 and expires the session`() {
        acceptOnly("never")
        val manager = TokenManager(
            FakeTokenStorage(Tokens("old", "r", now + 3_600_000)),
            CountingRefreshClient { RefreshResult.Rejected("invalid_grant") },
        ) { now }

        assertEquals(401, get(client(manager)))
        assertEquals(AuthState.SessionExpired, manager.state.value)
    }

    @Test
    fun `persistent 401 after a successful refresh is not retried forever`() {
        acceptOnly("never")
        val refresh = CountingRefreshClient()
        val manager = TokenManager(FakeTokenStorage(Tokens("old", "r", now + 3_600_000)), refresh) { now }

        assertEquals(401, get(client(manager)))
        assertEquals(1, refresh.calls)
        assertEquals(2, server.requestCount)
    }
}
