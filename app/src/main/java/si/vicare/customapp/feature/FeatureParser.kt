package si.vicare.customapp.feature

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Lenient parser for `…/features` responses. A malformed feature or property degrades to
 * [PropertyValue.Unknown] (or is skipped if it has no name) instead of failing the whole response.
 */
object FeatureParser {

    fun parse(body: String): List<Feature> {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val data = when (root) {
            is JsonObject -> root["data"] as? JsonArray
            is JsonArray -> root
            else -> null
        } ?: return emptyList()
        return data.mapNotNull { (it as? JsonObject)?.let(::parseFeature) }
    }

    fun parseFeature(json: JsonObject): Feature? {
        val name = json.string("feature") ?: return null
        return Feature(
            name = name,
            gatewayId = json.string("gatewayId"),
            deviceId = json.string("deviceId"),
            isEnabled = json.bool("isEnabled") ?: false,
            isReady = json.bool("isReady") ?: true,
            timestamp = json.string("timestamp"),
            properties = (json["properties"] as? JsonObject).orEmpty().mapValues { (_, v) -> parseProperty(v) },
            commands = (json["commands"] as? JsonObject).orEmpty().mapNotNull { (key, v) ->
                (v as? JsonObject)?.let { key to parseCommand(key, it) }
            }.toMap(),
            deprecated = json["deprecated"] as? JsonObject,
            raw = json,
        )
    }

    fun parseProperty(element: JsonElement): PropertyValue {
        val obj = element as? JsonObject ?: return PropertyValue.Unknown("unknown", element)
        val type = obj.string("type") ?: "unknown"
        val unit = obj.string("unit")?.takeIf { it.isNotEmpty() }
        val value = obj["value"] ?: JsonNull
        val primitive = value as? JsonPrimitive
        return when (type) {
            "number" -> primitive?.takeIf { !it.isString }?.doubleOrNull?.let { PropertyValue.Number(it, unit) }
            "string" -> primitive?.takeIf { it.isString }?.let { PropertyValue.Text(it.content, unit) }
            "boolean" -> primitive?.booleanOrNull?.let { PropertyValue.Bool(it, unit) }
            "array" -> (value as? JsonArray)?.let { PropertyValue.ArrayValue(it, unit) }
            else -> if (value is JsonObject || value is JsonArray) PropertyValue.ObjectValue(type, value, unit) else null
        } ?: PropertyValue.Unknown(type, value, unit)
    }

    private fun parseCommand(key: String, json: JsonObject) = Command(
        name = json.string("name") ?: key,
        uri = json.string("uri").orEmpty(),
        isExecutable = json.bool("isExecutable") ?: false,
        params = (json["params"] as? JsonObject).orEmpty().mapNotNull { (name, v) ->
            (v as? JsonObject)?.let { p ->
                name to CommandParam(
                    name = name,
                    type = p.string("type") ?: "unknown",
                    required = p.bool("required") ?: false,
                    constraints = parseConstraints(p["constraints"] as? JsonObject ?: JsonObject(emptyMap())),
                )
            }
        }.toMap(),
    )

    private fun parseConstraints(c: JsonObject) = Constraints(
        min = c.double("min"),
        max = c.double("max"),
        stepping = c.double("stepping"),
        enum = c.strings("enum"),
        regEx = c.string("regEx"),
        minLength = c.int("minLength"),
        maxLength = c.int("maxLength"),
        sameDayAllowed = c.bool("sameDayAllowed"),
        efficientLowerBorder = c.double("efficientLowerBorder"),
        efficientUpperBorder = c.double("efficientUpperBorder"),
        modes = c.strings("modes"),
        maxEntries = c.int("maxEntries"),
        resolution = c.int("resolution"),
        defaultMode = c.string("defaultMode"),
        overlapAllowed = c.bool("overlapAllowed"),
        raw = c,
    )

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.double(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.strings(key: String) =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
}
