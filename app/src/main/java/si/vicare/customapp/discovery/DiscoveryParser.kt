package si.vicare.customapp.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class GatewayRef(val installationId: String, val serial: String)

/** Extracts the identifiers needed to walk installation → gateway → device from equipment responses. */
object DiscoveryParser {

    /** `GET /iot/v2/equipment/installations?includeGateways=true` → gateways per installation (in order). */
    fun parseInstallations(body: String): Map<String, List<String>> =
        dataArray(body).mapNotNull { it as? JsonObject }.mapNotNull { installation ->
            val id = installation.primitive("id") ?: return@mapNotNull null
            val serials = (installation["gateways"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.primitive("serial") }
            id to serials
        }.toMap(LinkedHashMap())

    /** `GET /iot/v2/equipment/gateways/` → (installationId, serial) pairs. */
    fun parseGateways(body: String): List<GatewayRef> =
        dataArray(body).mapNotNull { it as? JsonObject }.mapNotNull { gateway ->
            val serial = gateway.primitive("serial") ?: return@mapNotNull null
            val installationId = gateway.primitive("installationId") ?: return@mapNotNull null
            GatewayRef(installationId, serial)
        }

    /** `GET …/gateways/{serial}/devices/` → device IDs (e.g. "0", "gateway"). */
    fun parseDeviceIds(body: String): List<String> =
        dataArray(body).mapNotNull { (it as? JsonObject)?.primitive("id") }

    /** Merges both equipment views so a gateway missing from one response is still discovered. */
    fun mergeGateways(installations: Map<String, List<String>>, gateways: List<GatewayRef>): List<GatewayRef> {
        val merged = LinkedHashSet<GatewayRef>()
        installations.forEach { (id, serials) -> serials.forEach { merged += GatewayRef(id, it) } }
        merged += gateways
        return merged.toList()
    }

    private fun dataArray(body: String): List<JsonElement> {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        return when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] as? JsonArray).orEmpty()
            else -> emptyList()
        }
    }

    private fun JsonObject.primitive(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
}
