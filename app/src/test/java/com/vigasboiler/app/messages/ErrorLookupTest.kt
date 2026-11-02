package com.vigasboiler.app.messages

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import com.vigasboiler.app.feature.FakeSelectionStore
import com.vigasboiler.app.feature.Selection
import com.vigasboiler.app.net.ViessmannApi
import com.vigasboiler.app.ratelimit.FakeBlockStore
import com.vigasboiler.app.ratelimit.FakeCallLog
import com.vigasboiler.app.ratelimit.RateLimitTracker

class ErrorLookupTest {

    private val server = MockWebServer()
    private val dump = Files.createTempDirectory("dump").toFile()
    private lateinit var repo: ErrorLookupRepository

    @Before fun setUp() {
        server.start()
        dump.resolve("04_devices_i0_g0.json").writeText("""{"data":[{"id":"0","deviceType":"heating","materialNumber":"7654321"}]}""")
        repo = ErrorLookupRepository(
            Retrofit.Builder().baseUrl(server.url("/")).build().create(ViessmannApi::class.java),
            RateLimitTracker(FakeCallLog(), FakeBlockStore()),
            FakeSelectionStore(Selection("i", "g", "0")),
            discoveryDir = dump,
            cacheDir = Files.createTempDirectory("cache").toFile(),
        )
    }

    @After fun tearDown() {
        server.close()
        dump.deleteRecursively()
    }

    @Test
    fun `a found code is read from the single-code request`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"data":[{"errorCode":"F.74","description":"Water pressure too low"}]}"""))
        val result = repo.lookup("F.74") as LookupResult.Found
        assertEquals("Water pressure too low", result.info.description)
        assertEquals("Viessmann error database", result.info.source)
        val request = server.takeRequest()
        assertEquals("/service-documents/v3/error-database", request.url.encodedPath)
        assertEquals("7654321", request.url.queryParameter("materialNumber"))
        assertEquals("F.74", request.url.queryParameter("errorCode"))
    }

    @Test
    fun `a 404 falls back to the full list once and both answers are cached`() = runTest {
        server.enqueue(MockResponse(code = 404, body = """{"errorType":"RESOURCE_NOT_FOUND"}"""))
        server.enqueue(MockResponse(code = 200, body = """{"data":[{"code":"S29","title":"Found in full list"},{"code":"S1","title":"Other"}]}"""))

        val first = repo.lookup("S.29")
        assertEquals("Found in full list", (first as LookupResult.Found).info.description)
        assertEquals(2, server.requestCount)
        assertEquals("S.29", server.takeRequest().url.queryParameter("errorCode")) // single code → 404
        assertNull(server.takeRequest().url.queryParameter("errorCode")) // full list

        // The same code again: both answers come from the cache.
        repo.lookup("S.29")
        assertEquals(2, server.requestCount)

        // Another code: one call for the code itself (404), the full list is reused from cache.
        server.enqueue(MockResponse(code = 404, body = """{"errorType":"RESOURCE_NOT_FOUND"}"""))
        assertEquals("Other", (repo.lookup("S.1") as LookupResult.Found).info.description)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a code missing everywhere is reported as not found`() = runTest {
        server.enqueue(MockResponse(code = 404))
        server.enqueue(MockResponse(code = 404))
        assertEquals(LookupResult.NotFound("S.29"), repo.lookup("S.29"))
        assertEquals(LookupResult.NotFound("S.29"), repo.lookup("S.29"))
        assertEquals(2, server.requestCount) // the 404s are cached
    }

    @Test
    fun `other HTTP errors are failures and not cached`() = runTest {
        server.enqueue(MockResponse(code = 500, body = """{"errorType":"INTERNAL","message":"boom"}"""))
        assertTrue(repo.lookup("F.74") is LookupResult.Failed)
        server.enqueue(MockResponse(code = 200, body = """{"data":[{"errorCode":"F.74","description":"ok"}]}"""))
        assertTrue(repo.lookup("F.74") is LookupResult.Found)
    }

    @Test
    fun `parser matches codes in any notation and reports only structure`() {
        val body = """{"data":[{"errorCode":"S29","description":"Desc","solutions":["Do this"]}]}"""
        val info = ErrorDatabaseParser.parse("S.29", body)!!
        assertEquals("Desc", info.description)
        assertEquals(listOf("Do this"), info.solutions)
        val shape = ErrorDatabaseParser.shape(body)
        assertTrue(shape, shape.startsWith("2 objects") && !shape.contains("Desc"))
        assertEquals("S.154-1", ErrorDatabaseParser.normalize("s154-1"))
        assertEquals("S.29", ErrorDatabaseParser.normalize("S 29"))
    }
}
