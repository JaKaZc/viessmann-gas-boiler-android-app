package com.vigasboiler.app.auth

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IamRefreshClientTest {

    private val server = MockWebServer()
    private lateinit var client: IamRefreshClient

    @Before fun setUp() {
        server.start()
        client = IamRefreshClient(OkHttpClient(), server.url("/idp/v3/token").toString()) { "my-client" }
    }

    @After fun tearDown() = server.close()

    @Test
    fun `sends a form encoded refresh grant and parses the response`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"access_token":"A","refresh_token":"R2","expires_in":3600,"token_type":"Bearer"}"""))

        val result = client.refresh("R1")

        assertEquals(RefreshResult.Success("A", "R2", 3600), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = request.body!!.utf8()
        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue(body.contains("client_id=my-client"))
        assertTrue(body.contains("refresh_token=R1"))
    }

    @Test
    fun `missing refresh token in response is allowed`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"access_token":"A","expires_in":1800}"""))
        assertEquals(RefreshResult.Success("A", null, 1800), client.refresh("R1"))
    }

    @Test
    fun `invalid_grant is a rejection`() = runTest {
        server.enqueue(MockResponse(code = 400, body = """{"error":"invalid_grant","error_description":"expired"}"""))
        assertEquals(RefreshResult.Rejected("invalid_grant"), client.refresh("R1"))
    }

    @Test
    fun `server error is transient`() = runTest {
        server.enqueue(MockResponse(code = 503, body = "unavailable"))
        assertTrue(client.refresh("R1") is RefreshResult.TransientFailure)
    }

    @Test
    fun `without a client id the refresh is rejected without a request`() = runTest {
        val noClient = IamRefreshClient(OkHttpClient(), server.url("/idp/v3/token").toString()) { null }
        assertEquals(RefreshResult.Rejected("no_client_id"), noClient.refresh("R1"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the current client id is read on every refresh`() = runTest {
        var id = "first-client"
        val changing = IamRefreshClient(OkHttpClient(), server.url("/idp/v3/token").toString()) { id }
        server.enqueue(MockResponse(code = 200, body = """{"access_token":"A","expires_in":60}"""))
        server.enqueue(MockResponse(code = 200, body = """{"access_token":"B","expires_in":60}"""))
        changing.refresh("R1")
        id = "second-client"
        changing.refresh("R1")
        assertTrue(server.takeRequest().body!!.utf8().contains("client_id=first-client"))
        assertTrue(server.takeRequest().body!!.utf8().contains("client_id=second-client"))
    }
}
