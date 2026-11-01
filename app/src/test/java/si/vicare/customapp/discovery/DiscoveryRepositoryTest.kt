package si.vicare.customapp.discovery

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import si.vicare.customapp.discovery.Samples.GATEWAY_SERIAL
import si.vicare.customapp.discovery.Samples.INSTALLATION_ID
import si.vicare.customapp.net.ViessmannApi

class DiscoveryRepositoryTest {

    private val server = MockWebServer()
    private lateinit var dumpDir: File
    private lateinit var repository: DiscoveryRepository
    private val paths = mutableListOf<String>()

    @Before fun setUp() {
        server.start()
        dumpDir = Files.createTempDirectory("dump").toFile()
        val api = Retrofit.Builder().baseUrl(server.url("/")).build().create(ViessmannApi::class.java)
        repository = DiscoveryRepository(api, dumpDir) { 42L }
    }

    @After fun tearDown() {
        server.close()
        dumpDir.deleteRecursively()
    }

    private fun serve(override: (String) -> MockResponse? = { null }) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
                synchronized(paths) { paths += path }
                override(path)?.let { return it }
                val base = "/iot/v2/features/installations/$INSTALLATION_ID"
                val body = when (path) {
                    "/users/v1/users/me?sections=identity" -> Samples.me
                    "/iot/v2/equipment/installations?includeGateways=true" -> Samples.installations
                    "/iot/v2/equipment/gateways/" -> Samples.gateways
                    "/iot/v2/equipment/installations/$INSTALLATION_ID/gateways/$GATEWAY_SERIAL/devices/" -> Samples.devices
                    "$base/features", "$base/gateways/$GATEWAY_SERIAL/features" -> """{"data":[]}"""
                    "$base/gateways/$GATEWAY_SERIAL/devices/0/features" -> Samples.deviceFeatures
                    "$base/gateways/$GATEWAY_SERIAL/devices/gateway/features" ->
                        return MockResponse(code = 404, body = """{"errorType":"DEVICE_NOT_FOUND"}""")
                    else -> return MockResponse(code = 500, body = "unexpected $path")
                }
                return MockResponse(code = 200, body = body)
            }
        }
    }

    @Test
    fun `calls endpoints in the specified order and stores every body`() = runTest {
        serve()
        val manifest = repository.run {}

        val base = "/iot/v2/features/installations/$INSTALLATION_ID"
        assertEquals(
            listOf(
                "/users/v1/users/me?sections=identity",
                "/iot/v2/equipment/installations?includeGateways=true",
                "/iot/v2/equipment/gateways/",
                "/iot/v2/equipment/installations/$INSTALLATION_ID/gateways/$GATEWAY_SERIAL/devices/",
                "$base/features",
                "$base/gateways/$GATEWAY_SERIAL/features",
                "$base/gateways/$GATEWAY_SERIAL/devices/gateway/features",
                "$base/gateways/$GATEWAY_SERIAL/devices/0/features",
            ),
            paths,
        )
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 7), manifest.entries.map { it.step })
        assertEquals(404, manifest.entries[6].httpStatus) // failure recorded, run continued
        assertEquals(Samples.deviceFeatures, File(dumpDir, "07_device_features_i0_g0_d1.json").readText())
        assertTrue(File(dumpDir, DiscoveryRepository.MANIFEST).exists())
        assertEquals(manifest, repository.lastManifest())
    }

    @Test
    fun `file names and manifest never contain identifiers`() = runTest {
        serve()
        repository.run {}
        val names = dumpDir.list()!!.joinToString()
        val manifest = File(dumpDir, DiscoveryRepository.MANIFEST).readText()
        listOf(INSTALLATION_ID, GATEWAY_SERIAL).forEach {
            assertTrue(it !in names)
            assertTrue(it !in manifest)
        }
    }

    @Test
    fun `429 stops the run immediately`() = runTest {
        serve { path ->
            if (path.startsWith("/iot/v2/equipment/gateways")) {
                MockResponse(code = 429, body = """{"errorType":"RATE_LIMIT_EXCEEDED","extendedPayload":{"limitReset":1}}""")
            } else {
                null
            }
        }
        val manifest = repository.run {}

        assertEquals(3, paths.size)
        assertEquals(429, manifest.entries.last().httpStatus)
    }

    @Test
    fun `runIfAllowed refuses a second run within the minimum interval`() = runTest {
        serve()
        var now = 1_000_000L
        val guarded = DiscoveryRepository(
            Retrofit.Builder().baseUrl(server.url("/")).build().create(ViessmannApi::class.java),
            dumpDir,
        ) { now }

        assertTrue(guarded.runIfAllowed {} != null)
        val callsAfterFirst = paths.size
        now += 30_000
        assertNull(guarded.runIfAllowed {})
        assertEquals(callsAfterFirst, paths.size)
        assertEquals(30_000L, guarded.millisUntilNextRun())
        now += 30_000
        assertTrue(guarded.runIfAllowed {} != null)
    }

    @Test
    fun `concurrent runIfAllowed calls perform only one run`() = runBlocking {
        serve()
        val results = (1..3).map { async(Dispatchers.IO) { repository.runIfAllowed {} } }.awaitAll()
        assertEquals(1, results.count { it != null })
        assertEquals(8, paths.size)
    }

    @Test
    fun `no manifest before the first run`() {
        assertNull(repository.lastManifest())
    }
}
