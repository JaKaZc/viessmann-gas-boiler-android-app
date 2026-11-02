package com.vigasboiler.app.feature

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import com.vigasboiler.app.net.ViessmannApi
import com.vigasboiler.app.ratelimit.CallKind
import com.vigasboiler.app.ratelimit.FakeBlockStore
import com.vigasboiler.app.ratelimit.FakeCallLog
import com.vigasboiler.app.ratelimit.RateLimitInterceptor
import com.vigasboiler.app.ratelimit.RateLimitTracker

/** Accepted commands show at once; stale reads from the boiler do not undo them. */
class CommandUpdateTest {

    private val server = MockWebServer()
    private var now = 50_000_000L
    private lateinit var dir: File
    private lateinit var base: String
    private lateinit var repo: FeatureRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What the "boiler" currently reports; the test changes it to simulate the delay. */
    @Volatile private var boilerSlope = 1.4
    private lateinit var fixture: String

    private fun deviceBody(): String {
        val features = FeatureParser.parse(fixture).map { f ->
            if (f.name == CURVE) CommandEffects.withValues(f.raw, mapOf("slope" to JsonPrimitive(boilerSlope))) else f.raw
        }
        return JsonObject(mapOf("data" to kotlinx.serialization.json.JsonArray(features))).toString()
    }

    @Before fun setUp() {
        server.start()
        base = server.url("/").toString().trimEnd('/')
        dir = Files.createTempDirectory("cmd").toFile()
        fixture = Fixtures.read("07_device_features_i0_g0_d0.json").replace("https://api.viessmann-climatesolutions.com", base)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val filter = request.url.queryParameter("filter")
                return when {
                    request.method == "POST" -> MockResponse(code = 200, body = """{"data":{"success":true}}""")
                    path.endsWith("/devices/0/features") && filter != null -> {
                        val one = FeatureParser.parse(deviceBody()).first { it.name == filter }.raw
                        MockResponse(code = 200, body = """{"data":[$one]}""")
                    }
                    path.endsWith("/devices/0/features") -> MockResponse(code = 200, body = deviceBody())
                    path.endsWith("/features") -> MockResponse(code = 200, body = Fixtures.read("06_gateway_features_i0_g0.json"))
                    else -> MockResponse(code = 404)
                }
            }
        }
        val tracker = RateLimitTracker(FakeCallLog(), FakeBlockStore()) { now }
        val client = OkHttpClient.Builder().addNetworkInterceptor(RateLimitInterceptor(tracker)).build()
        val api = Retrofit.Builder().baseUrl(server.url("/")).client(client).build().create(ViessmannApi::class.java)
        repo = FeatureRepository(
            api, tracker, FakeSelectionStore(Selection("ID_2", "SERIAL_1", "0")), base,
            File(dir, "cache"), File(dir, "dump"), clock = { now }, scope = scope, confirmDelayMillis = 200,
        )
    }

    @After fun tearDown() {
        scope.cancel()
        server.close()
        dir.deleteRecursively()
    }

    private fun slope() = repo.snapshot.value!![CURVE]!!.number("slope")!!
    private suspend fun setSlope(v: Double) = repo.execute(repo.snapshot.value!![CURVE]!!, "setCurve", mapOf("slope" to JsonPrimitive(v), "shift" to JsonPrimitive(0)))

    @Test
    fun `stale re-read shows the accepted value at once, unconfirmed`() = runBlocking {
        repo.refresh(CallKind.Manual)
        val outcome = setSlope(1.6) as CommandOutcome.Success
        assertFalse(outcome.confirmed)
        assertEquals(1.6, slope(), 0.0)
    }

    @Test
    fun `a stale full refresh does not undo the pending change`() = runBlocking {
        repo.refresh(CallKind.Manual)
        setSlope(1.6)
        repo.refresh(CallKind.Background) // boiler still reports 1.4
        assertEquals(1.6, slope(), 0.0)
    }

    @Test
    fun `the delayed read confirms once the boiler reports the change`() = runBlocking {
        repo.refresh(CallKind.Manual)
        setSlope(1.6)
        val before = server.requestCount
        boilerSlope = 1.6
        withTimeout(5_000) { while (server.requestCount == before) delay(20) }
        delay(100)
        // Confirmed: a later stale refresh would now be shown as-is because nothing is pending.
        boilerSlope = 1.2
        repo.refresh(CallKind.Background)
        assertEquals(1.2, slope(), 0.0)
    }

    @Test
    fun `an immediate matching re-read confirms right away`() = runBlocking {
        repo.refresh(CallKind.Manual)
        boilerSlope = 1.6 // the boiler applies instantly
        val outcome = setSlope(1.6) as CommandOutcome.Success
        assertTrue(outcome.confirmed)
        assertEquals(1.6, slope(), 0.0)
    }

    @Test
    fun `a pending change expires so the boiler wins eventually`() = runBlocking {
        repo.refresh(CallKind.Manual)
        setSlope(1.6)
        now += FeatureRepository.PENDING_MILLIS + 1
        repo.refresh(CallKind.Background)
        assertEquals(1.4, slope(), 0.0)
    }

    @Test
    fun `command effects map params to properties`() {
        val mode = Fixtures.boiler("heating.circuits.0.operating.modes.active")
        val effect = CommandEffects.apply(mode.raw, "setMode", buildJsonObject { put("mode", "standby") })
        assertEquals(mapOf("value" to JsonPrimitive("standby")), effect.changed)
        assertEquals("standby", FeatureParser.parseFeature(effect.raw)!!.text())

        val normal = Fixtures.boiler("heating.circuits.0.operating.programs.normal")
        val t = CommandEffects.apply(normal.raw, "setTemperature", buildJsonObject { put("targetTemperature", 22) })
        assertEquals(22.0, FeatureParser.parseFeature(t.raw)!!.number("temperature")!!, 0.0)
        // 22 and 22.0 are the same reading.
        assertTrue(CommandEffects.confirms(FeatureParser.parseFeature(CommandEffects.withValues(normal.raw, mapOf("temperature" to JsonPrimitive(22.0))))!!, t.changed))

        val holiday = Fixtures.boiler("heating.operating.programs.holiday")
        val h = CommandEffects.apply(holiday.raw, "schedule", buildJsonObject { put("start", "2026-12-24"); put("end", "2027-01-02") })
        assertEquals(setOf("start", "end", "active"), h.changed.keys)
    }

    private companion object {
        const val CURVE = "heating.circuits.0.heating.curve"
    }
}
