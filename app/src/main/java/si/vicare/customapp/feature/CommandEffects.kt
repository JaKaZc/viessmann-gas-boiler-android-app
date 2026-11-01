package si.vicare.customapp.feature

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** The feature after a command, and which properties the command set to which values. */
data class CommandEffect(val raw: JsonObject, val changed: Map<String, JsonElement>)

/**
 * What a command does to its feature's properties, e.g. `setMode {mode}` → `value`,
 * `setTemperature {targetTemperature}` → `temperature`, `setCurve` → `slope`/`shift`.
 * Used to show an accepted change at once (before the boiler reports it) and by the mock API.
 */
object CommandEffects {

    fun apply(raw: JsonObject, command: String, params: Map<String, JsonElement>): CommandEffect {
        val props = (raw["properties"] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
        val changed = LinkedHashMap<String, JsonElement>()
        fun set(property: String, value: JsonElement) {
            val old = props[property] as? JsonObject ?: return
            props[property] = JsonObject(old + ("value" to value))
            changed[property] = value
        }
        when (command) {
            "schedule" -> {
                params["start"]?.let { set("start", it) }
                params["end"]?.let { set("end", it) }
                set("active", JsonPrimitive(true))
            }
            "unschedule" -> {
                set("start", JsonPrimitive(""))
                set("end", JsonPrimitive(""))
                set("active", JsonPrimitive(false))
            }
            "activate" -> set("active", JsonPrimitive(true))
            "deactivate" -> set("active", JsonPrimitive(false))
            else -> params.forEach { (name, value) -> set(PARAM_TO_PROPERTY[name]?.firstOrNull { it in props } ?: name, value) }
        }
        return CommandEffect(JsonObject(raw + ("properties" to JsonObject(props))), changed)
    }

    /** [raw] with the given property values set (properties the feature lacks are ignored). */
    fun withValues(raw: JsonObject, values: Map<String, JsonElement>): JsonObject {
        val props = (raw["properties"] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
        values.forEach { (k, v) -> (props[k] as? JsonObject)?.let { props[k] = JsonObject(it + ("value" to v)) } }
        return JsonObject(raw + ("properties" to JsonObject(props)))
    }

    /** True when [fetched] already reports every value in [changed] (numbers compared numerically). */
    fun confirms(fetched: Feature, changed: Map<String, JsonElement>): Boolean {
        val props = fetched.raw["properties"] as? JsonObject ?: return false
        return changed.all { (property, expected) ->
            val actual = (props[property] as? JsonObject)?.get("value") ?: return@all false
            sameValue(actual, expected)
        }
    }

    private fun sameValue(a: JsonElement, b: JsonElement): Boolean {
        val pa = a as? JsonPrimitive
        val pb = b as? JsonPrimitive
        if (pa != null && pb != null && !pa.isString && !pb.isString) {
            val da = pa.doubleOrNull
            val db = pb.doubleOrNull
            if (da != null && db != null) return kotlin.math.abs(da - db) < 1e-6
        }
        return a == b
    }

    private val PARAM_TO_PROPERTY = mapOf(
        "mode" to listOf("value"),
        "targetTemperature" to listOf("temperature", "value"),
        "temperature" to listOf("value", "temperature"),
        "newSchedule" to listOf("entries"),
        "name" to listOf("name", "value"),
    )
}
