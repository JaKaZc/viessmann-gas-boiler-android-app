package com.vigasboiler.app.ratelimit

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakeCallLog : ApiCallLog {
    val calls = mutableListOf<Long>()
    override suspend fun record(timestampMillis: Long) { calls += timestampMillis }
    override suspend fun countSince(sinceMillis: Long) = calls.count { it >= sinceMillis }
    override suspend fun pruneBefore(beforeMillis: Long) { calls.removeAll { it < beforeMillis } }
}

class FakeBlockStore(var block: RateLimitBlock? = null) : RateLimitBlockStore {
    override suspend fun read() = block
    override suspend fun write(block: RateLimitBlock?) { this.block = block }
}

class RateLimitTrackerTest {

    private var now = 1_000_000_000L
    private val log = FakeCallLog()
    private val store = FakeBlockStore()
    private val tracker = RateLimitTracker(log, store) { now }

    private suspend fun calls(n: Int, spacingMillis: Long = 0) = repeat(n) {
        tracker.onCall()
        now += spacingMillis
    }

    @Test
    fun `sliding windows count only recent calls`() = runTest {
        calls(10)
        now += 9 * 60_000
        calls(5)
        assertEquals(15, tracker.currentBudget().used10m)
        now += 2 * 60_000 // the first 10 calls are now older than 10 minutes
        val budget = tracker.currentBudget()
        assertEquals(5, budget.used10m)
        assertEquals(15, budget.used24h)
        assertEquals(115, budget.remaining10m)
        assertEquals(1435, budget.remaining24h)
    }

    @Test
    fun `calls older than 24 hours are pruned`() = runTest {
        calls(3)
        now += RateLimitTracker.WINDOW_24H + 1
        calls(1)
        assertEquals(1, log.calls.size)
        assertEquals(1, tracker.currentBudget().used24h)
    }

    @Test
    fun `soft limit at 80 percent allows only manual refreshes and commands`() = runTest {
        calls(95)
        assertEquals(CallPermission.Allowed, tracker.check(CallKind.Background))
        calls(1) // 96 / 120 = 80 %
        assertTrue(tracker.check(CallKind.Background) is CallPermission.SoftLimited)
        assertTrue(tracker.check(CallKind.Automatic) is CallPermission.SoftLimited)
        assertEquals(CallPermission.Allowed, tracker.check(CallKind.Manual))
        assertEquals(CallPermission.Allowed, tracker.check(CallKind.Command))
    }

    @Test
    fun `daily soft limit works independently of the 10 minute window`() = runTest {
        calls(1160, spacingMillis = 60_000) // spread out: never more than 10 per 10 minutes
        val budget = tracker.currentBudget()
        assertTrue(budget.used10m <= 10)
        assertTrue(budget.isSoftLimited) // 1160 / 1450 = 80 %
        assertTrue(tracker.check(CallKind.Background) is CallPermission.SoftLimited)
    }

    @Test
    fun `nothing may cross a hard limit, not even manual actions`() = runTest {
        calls(119)
        assertEquals(CallPermission.Allowed, tracker.check(CallKind.Manual, calls = 1))
        assertTrue(tracker.check(CallKind.Manual, calls = 2) is CallPermission.WouldExceed)
        calls(1)
        assertTrue(tracker.check(CallKind.Command) is CallPermission.WouldExceed)
    }

    @Test
    fun `429 blocks everything until limitReset`() = runTest {
        tracker.onRateLimited("""{"errorType":"RATE_LIMIT_EXCEEDED","extendedPayload":{"name":"ViCare ten minutes limit","requestCountLimit":120,"limitReset":${now + 300_000}}}""")
        val blocked = tracker.check(CallKind.Command)
        assertTrue(blocked is CallPermission.Blocked)
        assertEquals(now + 300_000, (blocked as CallPermission.Blocked).block.untilMillis)
        assertEquals("ViCare ten minutes limit", blocked.block.limitName)
        now += 300_001
        assertEquals(CallPermission.Allowed, tracker.check(CallKind.Command))
        assertNull(store.block)
    }

    @Test
    fun `429 without reset falls back to the window of the named limit`() {
        assertEquals(now + RateLimitTracker.WINDOW_24H, RateLimitErrorParser.parse("""{"extendedPayload":{"name":"ViCare day limit"}}""", now).untilMillis)
        assertEquals(now + RateLimitTracker.WINDOW_24H, RateLimitErrorParser.parse("""{"extendedPayload":{"requestCountLimit":1450}}""", now).untilMillis)
        assertEquals(now + RateLimitTracker.WINDOW_10M, RateLimitErrorParser.parse("not json", now).untilMillis)
        // A reset in the past is ignored.
        assertEquals(now + RateLimitTracker.WINDOW_10M, RateLimitErrorParser.parse("""{"extendedPayload":{"limitReset":1}}""", now).untilMillis)
    }

    @Test
    fun `interceptor counts calls, records 429 and then stops sending`() = runTest {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse(code = 200, body = "{}"))
        server.enqueue(MockResponse(code = 429, body = """{"extendedPayload":{"name":"ViCare day limit","limitReset":${now + 3_600_000}}}"""))
        val client = OkHttpClient.Builder().addNetworkInterceptor(RateLimitInterceptor(tracker)).build()
        fun get() = client.newCall(Request.Builder().url(server.url("/x")).build()).execute().use { it.code }

        assertEquals(200, get())
        assertEquals(429, get())
        try {
            get()
            fail("expected RateLimitedException")
        } catch (e: RateLimitedException) {
            assertEquals(now + 3_600_000, e.block.untilMillis)
        }
        assertEquals(2, server.requestCount) // the third request never left the device
        assertEquals(2, log.calls.size)
        server.close()
    }
}
