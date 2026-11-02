package com.vigasboiler.app.feature

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The heating device the curated screens show. */
data class Selection(val installationId: String, val gatewaySerial: String, val deviceId: String)

interface SelectionStore {
    suspend fun read(): Selection?
    suspend fun write(selection: Selection?)
}

private val Context.selectionDataStore: DataStore<Preferences> by preferencesDataStore(name = "selection")

class DataStoreSelectionStore(private val context: Context) : SelectionStore {
    override suspend fun read(): Selection? {
        val p = context.selectionDataStore.data.first()
        return Selection(p[INSTALLATION] ?: return null, p[GATEWAY] ?: return null, p[DEVICE] ?: return null)
    }

    override suspend fun write(selection: Selection?) {
        context.selectionDataStore.edit { p ->
            if (selection == null) {
                p.clear()
            } else {
                p[INSTALLATION] = selection.installationId
                p[GATEWAY] = selection.gatewaySerial
                p[DEVICE] = selection.deviceId
            }
        }
    }

    private companion object {
        val INSTALLATION = stringPreferencesKey("installation_id")
        val GATEWAY = stringPreferencesKey("gateway_serial")
        val DEVICE = stringPreferencesKey("device_id")
    }
}

object SelectionParser {
    /**
     * Picks the first heating device from `GET /iot/v2/equipment/installations?includeGateways=true`,
     * whose gateways embed their devices. Falls back to device `0`, the usual boiler ID on E3.
     */
    fun fromInstallations(body: String): Selection? {
        val data = runCatching { Json.parseToJsonElement(body) }.getOrNull()
            .let { (it as? JsonObject)?.get("data") as? JsonArray } ?: return null
        for (installation in data.filterIsInstance<JsonObject>()) {
            val installationId = installation.str("id") ?: continue
            for (gateway in (installation["gateways"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()) {
                val serial = gateway.str("serial") ?: continue
                val devices = (gateway["devices"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                val heating = devices.firstOrNull { it.str("deviceType") == "heating" } ?: devices.firstOrNull { it.str("id") == "0" }
                return Selection(installationId, serial, heating?.str("id") ?: "0")
            }
        }
        return null
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
}
