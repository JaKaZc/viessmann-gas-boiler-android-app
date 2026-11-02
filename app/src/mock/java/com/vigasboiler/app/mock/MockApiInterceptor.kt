package com.vigasboiler.app.mock

import android.content.res.AssetManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import com.vigasboiler.app.feature.CommandEffects

/**
 * Answers every API request from the redacted dump (docs/feature-dump, packaged as assets).
 * Commands are applied to an in-memory copy of the device features, so the UI reacts as it
 * would against the real boiler. Nothing reaches the network.
 */
class MockApiInterceptor(private val assets: AssetManager) : Interceptor {

    private val features = MockFeatureStore(asset("07_device_features_i0_g0_d0.json"))

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        Thread.sleep(LATENCY_MILLIS)
        val path = request.url.encodedPath
        val (code, body) = when {
            request.method == "POST" && path.contains("/commands/") -> {
                val (feature, command) = commandTarget(path)
                val params = Buffer().also { request.body?.writeTo(it) }.readUtf8()
                    .let { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() } ?: JsonObject(emptyMap())
                features.apply(feature, command, params)
            }
            path.startsWith("/users/v1/users/me") -> 200 to asset("01_users_me.json")
            path == "/iot/v2/equipment/installations" -> 200 to asset("02_installations.json")
            path.startsWith("/iot/v2/equipment/gateways") -> 200 to asset("03_gateways.json")
            path.endsWith("/devices/") -> 200 to asset("04_devices_i0_g0.json")
            path.matches(Regex(".*/devices/0/features")) -> 200 to features.body(request.url.queryParameter("filter"))
            path.matches(Regex(".*/devices/TCU/features")) -> 200 to asset("07_device_features_i0_g0_d1.json")
            path.matches(Regex(".*/gateways/[^/]+/features")) -> 200 to asset("06_gateway_features_i0_g0.json")
            path.matches(Regex(".*/installations/[^/]+/features")) -> 200 to asset("05_installation_features_i0.json")
            else -> 404 to """{"statusCode":404,"errorType":"NOT_FOUND","message":"No mock for $path"}"""
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Mock error")
            .body(body.toResponseBody(JSON))
            .build()
    }

    private fun asset(name: String): String = assets.open(name).bufferedReader().use { it.readText() }

    companion object {
        private const val LATENCY_MILLIS = 300L
        private val JSON = "application/json".toMediaType()

        /** `…/iot/v2/features/…/devices/0/features/<feature>/commands/<command>` → (feature, command). */
        fun commandTarget(path: String): Pair<String, String> =
            path.substringAfterLast("/features/").substringBefore("/commands/") to path.substringAfterLast("/")
    }
}

/** In-memory device features that commands can change. */
class MockFeatureStore(initialBody: String) {
    private val features: MutableMap<String, JsonObject> =
        ((Json.parseToJsonElement(initialBody) as JsonObject)["data"] as JsonArray)
            .filterIsInstance<JsonObject>()
            .associateByTo(LinkedHashMap()) { (it["feature"] as JsonPrimitive).content }

    @Synchronized
    fun body(filter: String?): String {
        val names = filter?.split(',')?.map { it.trim() }?.toSet()
        val selected = features.values.filter { names == null || (it["feature"] as JsonPrimitive).content in names }
        return JsonObject(mapOf("data" to JsonArray(selected))).toString()
    }

    /** Returns (HTTP code, body). Parameters are written to the property they describe. */
    @Synchronized
    fun apply(featureName: String, command: String, params: JsonObject): Pair<Int, String> {
        val feature = features[featureName] ?: return 404 to """{"statusCode":404,"errorType":"FEATURE_NOT_FOUND"}"""
        val cmd = (feature["commands"] as? JsonObject)?.get(command) as? JsonObject
            ?: return 404 to """{"statusCode":404,"errorType":"COMMAND_NOT_FOUND"}"""
        if ((cmd["isExecutable"] as? JsonPrimitive)?.content != "true") {
            return 403 to """{"statusCode":403,"errorType":"FORBIDDEN","message":"Command not executable"}"""
        }
        features[featureName] = CommandEffects.apply(feature, command, params).raw
        return 200 to """{"data":{"success":true,"reason":"COMMAND_EXECUTION_SUCCESS"}}"""
    }

}
