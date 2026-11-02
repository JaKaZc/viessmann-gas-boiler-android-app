package com.vigasboiler.app.discovery

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import retrofit2.Response
import com.vigasboiler.app.net.ViessmannApi

@Serializable
data class DumpEntry(
    val step: Int,
    val fileName: String?,
    /** Endpoint with placeholders instead of IDs, safe to show and to share. */
    val endpoint: String,
    val httpStatus: Int?,
    val bytes: Long,
    val error: String? = null,
    val timestampMillis: Long,
) {
    val isSuccess: Boolean get() = httpStatus in 200..299
}

@Serializable
data class DumpManifest(val createdAtMillis: Long, val entries: List<DumpEntry>)

/**
 * Runs the discovery calls of step 1 in order and stores each raw response body in
 * app-internal storage. A failing call (403 in the Basic plan, 404, …) is recorded and the run
 * continues; a 429 stops the run immediately so no further budget is spent.
 */
class DiscoveryRepository(
    private val api: ViessmannApi,
    private val dumpDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { prettyPrint = true }
    private val runLock = Mutex()

    /**
     * Runs discovery unless another run is in progress or the last one finished less than
     * [MIN_INTERVAL_MILLIS] ago. Returns null when skipped. The repository is a singleton, so this
     * also protects against several screens or activity instances starting runs at once.
     */
    suspend fun runIfAllowed(onProgress: (DumpEntry) -> Unit): DumpManifest? {
        if (!runLock.tryLock()) return null
        try {
            if (millisUntilNextRun() > 0) return null
            return run(onProgress)
        } finally {
            runLock.unlock()
        }
    }

    fun millisUntilNextRun(): Long {
        val last = lastManifest()?.createdAtMillis ?: return 0
        return (last + MIN_INTERVAL_MILLIS - clock()).coerceAtLeast(0)
    }

    suspend fun run(onProgress: (DumpEntry) -> Unit): DumpManifest = withContext(Dispatchers.IO) {
        dumpDir.deleteRecursively()
        dumpDir.mkdirs()
        val entries = mutableListOf<DumpEntry>()
        var rateLimited = false

        suspend fun call(step: Int, fileName: String, endpoint: String, block: suspend () -> Response<ResponseBody>): String? {
            if (rateLimited) return null
            var body: String? = null
            val entry = try {
                val response = block()
                val text = (response.body() ?: response.errorBody())?.string().orEmpty()
                body = text
                File(dumpDir, fileName).writeText(text)
                if (response.code() == 429) rateLimited = true
                DumpEntry(step, fileName, endpoint, response.code(), text.length.toLong(), timestampMillis = clock())
            } catch (e: IOException) {
                DumpEntry(step, null, endpoint, null, 0, e.javaClass.simpleName + ": " + e.message, clock())
            }
            entries += entry
            onProgress(entry)
            return body.takeIf { entry.isSuccess }
        }

        call(1, "01_users_me.json", "GET /users/v1/users/me?sections=identity") { api.me() }
        val installationsBody = call(2, "02_installations.json", "GET /iot/v2/equipment/installations?includeGateways=true") {
            api.installations()
        }
        val gatewaysBody = call(3, "03_gateways.json", "GET /iot/v2/equipment/gateways/") { api.gateways() }

        val installations = installationsBody?.let(DiscoveryParser::parseInstallations).orEmpty()
        val gateways = DiscoveryParser.mergeGateways(installations, gatewaysBody?.let(DiscoveryParser::parseGateways).orEmpty())
        val installationIds = (installations.keys + gateways.map { it.installationId }).distinct()
        // Indices instead of real IDs in file names keep the dump shareable after redaction.
        val iIndex = installationIds.withIndex().associate { it.value to it.index }
        val gIndex = gateways.withIndex().associate { it.value to it.index }
        fun tag(gw: GatewayRef) = "i${iIndex.getValue(gw.installationId)}_g${gIndex.getValue(gw)}"

        val devicesByGateway = LinkedHashMap<GatewayRef, List<String>>()
        for (gw in gateways) {
            val body = call(4, "04_devices_${tag(gw)}.json", "GET /iot/v2/equipment/installations/{installationId}/gateways/{gatewaySerial}/devices/") {
                api.devices(gw.installationId, gw.serial)
            }
            devicesByGateway[gw] = body?.let(DiscoveryParser::parseDeviceIds).orEmpty()
        }
        for (id in installationIds) {
            call(5, "05_installation_features_i${iIndex.getValue(id)}.json", "GET /iot/v2/features/installations/{installationId}/features") {
                api.installationFeatures(id)
            }
        }
        for (gw in gateways) {
            call(6, "06_gateway_features_${tag(gw)}.json", "GET /iot/v2/features/installations/{installationId}/gateways/{gatewaySerial}/features") {
                api.gatewayFeatures(gw.installationId, gw.serial)
            }
        }
        for ((gw, deviceIds) in devicesByGateway) {
            deviceIds.forEachIndexed { d, deviceId ->
                // Device IDs can embed MAC addresses (e.g. Zigbee devices), so only the index goes into the name.
                call(7, "07_device_features_${tag(gw)}_d$d.json", "GET /iot/v2/features/installations/{installationId}/gateways/{gatewaySerial}/devices/{deviceId}/features") {
                    api.deviceFeatures(gw.installationId, gw.serial, deviceId)
                }
            }
        }

        val manifest = DumpManifest(clock(), entries)
        File(dumpDir, MANIFEST).writeText(json.encodeToString(DumpManifest.serializer(), manifest))
        manifest
    }

    fun lastManifest(): DumpManifest? =
        File(dumpDir, MANIFEST).takeIf { it.exists() }
            ?.let { runCatching { json.decodeFromString(DumpManifest.serializer(), it.readText()) }.getOrNull() }

    companion object {
        const val MANIFEST = "manifest.json"
        const val MIN_INTERVAL_MILLIS = 60_000L
    }
}
