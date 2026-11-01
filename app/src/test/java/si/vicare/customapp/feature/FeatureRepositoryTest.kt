package si.vicare.customapp.feature

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import si.vicare.customapp.net.ViessmannApi
import si.vicare.customapp.ratelimit.CallKind
import si.vicare.customapp.ratelimit.CallPermission
import si.vicare.customapp.ratelimit.FakeBlockStore
import si.vicare.customapp.ratelimit.FakeCallLog
import si.vicare.customapp.ratelimit.RateLimitInterceptor
import si.vicare.customapp.ratelimit.RateLimitTracker

class FakeSelectionStore(var selection: Selection? = null) : SelectionStore {
    override suspend fun read() = selection
    override suspend fun write(selection: Selection?) { this.selection = selection }
}

class FeatureRepositoryTest {

    private val server = MockWebServer()
    private var now = 10_000_000L
    private val log = FakeCallLog()
    private val tracker = RateLimitTracker(log, FakeBlockStore()) { now }
    private val selection = Selection("ID_2", "SERIAL_1", "0")
    private val selectionStore = FakeSelectionStore(selection)
    private lateinit var dir: File
    private lateinit var repo: FeatureRepository
    private lateinit var base: String
    private val requests = mutableListOf<RecordedRequest>()
    var commandResponse = MockResponse(code = 200, body = """{"data":{"success":true,"reason":"COMMAND_EXECUTION_SUCCESS"}}""")
    private val snapshots = mutableListOf<FeatureSnapshot>()

    @Before fun setUp() {
        server.start()
        base = server.url("/").toString().trimEnd('/')
        dir = Files.createTempDirectory("repo").toFile()
        // Command URIs in the fixture point at the real API; point them at the mock server.
        val device = Fixtures.read("07_device_features_i0_g0_d0.json").replace("https://api.viessmann-climatesolutions.com", base)
        val gateway = Fixtures.read("06_gateway_features_i0_g0.json")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                val path = request.url.encodedPath
                val filter = request.url.queryParameter("filter")
                return when {
                    request.method == "POST" -> commandResponse
                    path.endsWith("/devices/0/features") && filter != null -> {
                        val one = FeatureParser.parse(device).first { it.name == filter }.raw
                        MockResponse(code = 200, body = """{"data":[$one]}""")
                    }
                    path.endsWith("/devices/0/features") -> MockResponse(code = 200, body = device)
                    path.endsWith("/gateways/SERIAL_1/features") -> MockResponse(code = 200, body = gateway)
                    path == "/iot/v2/equipment/installations" -> MockResponse(code = 200, body = Fixtures.read("02_installations.json"))
                    else -> MockResponse(code = 404)
                }
            }
        }
        val client = OkHttpClient.Builder().addNetworkInterceptor(RateLimitInterceptor(tracker)).build()
        val api = Retrofit.Builder().baseUrl(server.url("/")).client(client).build().create(ViessmannApi::class.java)
        repo = FeatureRepository(api, tracker, selectionStore, base, File(dir, "cache"), File(dir, "dump"),
            listOf(SnapshotListener { _, s -> snapshots += s }), clock = { now })
    }

    @After fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    @Test
    fun `full refresh costs two calls and notifies listeners`() = runTest {
        val outcome = repo.refresh(CallKind.Manual)
        assertTrue(outcome is RefreshOutcome.Success)
        val snapshot = (outcome as RefreshOutcome.Success).snapshot
        assertEquals(110, snapshot.device.size)
        assertEquals(2, snapshot.gateway.size)
        assertEquals(41.9, snapshot["heating.boiler.sensors.temperature.commonSupply"]!!.number()!!, 0.0)
        assertEquals(2, log.calls.size)
        assertEquals(1, snapshots.size)
    }

    @Test
    fun `foreground refreshes are at least 60 seconds apart, background ones are not`() = runTest {
        repo.refresh(CallKind.Manual)
        now += 30_000
        val tooSoon = repo.refresh(CallKind.Manual)
        assertEquals(RefreshOutcome.TooSoon(30_000), tooSoon)
        assertTrue(repo.refresh(CallKind.Background) is RefreshOutcome.Success)
        now += 60_000
        assertTrue(repo.refresh(CallKind.Automatic) is RefreshOutcome.Success)
        assertEquals(6, log.calls.size)
    }

    @Test
    fun `soft limit stops automatic refreshes before any request`() = runTest {
        repeat(96) { tracker.onCall() }
        val outcome = repo.refresh(CallKind.Automatic)
        assertTrue(outcome is RefreshOutcome.NotAllowed && outcome.permission is CallPermission.SoftLimited)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cached snapshot is available after restart without calls`() = runTest {
        repo.refresh(CallKind.Manual)
        val api = Retrofit.Builder().baseUrl(server.url("/")).build().create(ViessmannApi::class.java)
        val fresh = FeatureRepository(api, tracker, selectionStore, base, File(dir, "cache"), File(dir, "dump"), clock = { now })
        fresh.loadCached()
        assertEquals(110, fresh.snapshot.value!!.device.size)
        assertEquals(now, fresh.snapshot.value!!.fetchedAtMillis)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `invalid command values never reach the network`() = runTest {
        repo.refresh(CallKind.Manual)
        val curve = repo.snapshot.value!!["heating.circuits.0.heating.curve"]!!
        val outcome = repo.execute(curve, "setCurve", mapOf("slope" to JsonPrimitive(9), "shift" to JsonPrimitive(0)))
        assertTrue(outcome is CommandOutcome.Invalid)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `successful command posts the body and re-reads only that feature`() = runTest {
        repo.refresh(CallKind.Manual)
        val curve = repo.snapshot.value!!["heating.circuits.0.heating.curve"]!!
        val outcome = repo.execute(curve, "setCurve", mapOf("slope" to JsonPrimitive(1.3), "shift" to JsonPrimitive(2)))
        assertTrue(outcome.toString(), outcome is CommandOutcome.Success)
        assertNotNull((outcome as CommandOutcome.Success).feature)
        val post = requests.first { it.method == "POST" }
        assertTrue(post.url.encodedPath.endsWith("/heating.circuits.0.heating.curve/commands/setCurve"))
        assertEquals("""{"slope":1.3,"shift":2}""", post.body!!.utf8())
        val reRead = requests.last()
        assertEquals("heating.circuits.0.heating.curve", reRead.url.queryParameter("filter"))
    }

    @Test
    fun `403 means not in the Basic plan and other errors are readable`() = runTest {
        repo.refresh(CallKind.Manual)
        val mode = repo.snapshot.value!!["heating.circuits.0.operating.modes.active"]!!
        commandResponse = MockResponse(code = 403, body = """{"statusCode":403,"errorType":"FORBIDDEN","message":"Feature not available in your plan"}""")
        val forbidden = repo.execute(mode, "setMode", mapOf("mode" to JsonPrimitive("standby")))
        assertEquals(CommandOutcome.NotInPlan("HTTP 403: FORBIDDEN · Feature not available in your plan"), forbidden)

        commandResponse = MockResponse(code = 502, body = """{"statusCode":502,"errorType":"DEVICE_COMMUNICATION_ERROR","message":"Device not reachable"}""")
        assertEquals(
            CommandOutcome.Failed("HTTP 502: DEVICE_COMMUNICATION_ERROR · Device not reachable"),
            repo.execute(mode, "setMode", mapOf("mode" to JsonPrimitive("standby"))),
        )
    }

    @Test
    fun `non executable commands are refused locally`() = runTest {
        repo.refresh(CallKind.Manual)
        val levels = repo.snapshot.value!!["heating.circuits.0.temperature.levels"]!!
        assertEquals(CommandOutcome.NotExecutable, repo.execute(levels, "setMax", mapOf("temperature" to JsonPrimitive(70))))
    }

    @Test
    fun `command uri outside the API host is refused`() = runTest {
        repo.refresh(CallKind.Manual)
        val mode = repo.snapshot.value!!["heating.circuits.0.operating.modes.active"]!!
        val evil = mode.copy(commands = mode.commands.mapValues { (_, c) -> c.copy(uri = "https://evil.example/steal") })
        assertTrue(repo.execute(evil, "setMode", mapOf("mode" to JsonPrimitive("standby"))) is CommandOutcome.Failed)
        assertTrue(requests.none { it.method == "POST" })
    }

    @Test
    fun `selection resolves from the discovery dump without a call`() = runTest {
        selectionStore.selection = null
        File(dir, "dump").mkdirs()
        File(dir, "dump/02_installations.json").writeText(Fixtures.read("02_installations.json"))
        assertEquals(Selection("ID_2", "SERIAL_1", "0"), repo.resolveSelection())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `selection falls back to one equipment call`() = runTest {
        selectionStore.selection = null
        assertEquals(Selection("ID_2", "SERIAL_1", "0"), repo.resolveSelection())
        assertEquals(1, server.requestCount)
    }
}
