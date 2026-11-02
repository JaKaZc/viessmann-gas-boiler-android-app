package com.vigasboiler.app.feature

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Status of the gateway (TCU) from `gateway.devices`, e.g. "online". */
fun FeatureSnapshot.gatewayStatus(): String? {
    val devices = gatewayFeature("gateway.devices")?.properties?.get("devices") as? PropertyValue.ObjectValue ?: return null
    val list = devices.value as? JsonArray ?: return null
    val tcu = list.filterIsInstance<JsonObject>().firstOrNull { (it["type"] as? JsonPrimitive)?.content == "tcu" } ?: list.firstOrNull() as? JsonObject
    return (tcu?.get("status") as? JsonPrimitive)?.content
}
