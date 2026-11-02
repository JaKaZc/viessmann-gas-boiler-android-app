package com.vigasboiler.app.feature

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One entry of a `…/features` response, e.g. `heating.circuits.0.operating.modes.active`. */
data class Feature(
    val name: String,
    val gatewayId: String?,
    val deviceId: String?,
    val isEnabled: Boolean,
    val isReady: Boolean,
    val timestamp: String?,
    val properties: Map<String, PropertyValue>,
    val commands: Map<String, Command>,
    /** Set when the API marks the feature as deprecated (contains removalDate/info). */
    val deprecated: JsonObject?,
    val raw: JsonObject,
) {
    /** Prefix used to group the explorer, e.g. `heating.circuits` for `heating.circuits.0.name`. */
    val group: String get() = name.split('.').takeWhile { it.toIntOrNull() == null }.take(2).joinToString(".")

    fun number(property: String = "value"): Double? = (properties[property] as? PropertyValue.Number)?.value
    fun text(property: String = "value"): String? = (properties[property] as? PropertyValue.Text)?.value
    fun bool(property: String = "value"): Boolean? = (properties[property] as? PropertyValue.Bool)?.value
    fun unit(property: String = "value"): String? = properties[property]?.unit
}

/**
 * Domestic hot water features (`heating.dhw.*`, `…dhwCylinderSize`, `…summary.dhw`). Hidden by
 * default because hot water comes from a separate system.
 */
val Feature.isDhw: Boolean get() = name.split('.').any { it.startsWith("dhw", ignoreCase = true) }

/** Typed property value. Types the app does not know stay available as raw JSON. */
sealed interface PropertyValue {
    val type: String
    val unit: String?

    data class Number(val value: Double, override val unit: String?) : PropertyValue {
        override val type get() = "number"
    }

    data class Text(val value: String, override val unit: String?) : PropertyValue {
        override val type get() = "string"
    }

    data class Bool(val value: Boolean, override val unit: String? = null) : PropertyValue {
        override val type get() = "boolean"
    }

    /** `array` values, e.g. message entries; elements stay raw because their shape varies. */
    data class ArrayValue(val items: List<JsonElement>, override val unit: String? = null) : PropertyValue {
        override val type get() = "array"
    }

    /** Structured values with their own type name, e.g. `Schedule`, `DeviceList`, `VinEntry`. */
    data class ObjectValue(override val type: String, val value: JsonElement, override val unit: String? = null) : PropertyValue

    /** Anything that did not match the declared type. Never dropped, never fails parsing. */
    data class Unknown(override val type: String, val raw: JsonElement, override val unit: String? = null) : PropertyValue
}

data class Command(
    val name: String,
    val uri: String,
    val isExecutable: Boolean,
    val params: Map<String, CommandParam>,
)

data class CommandParam(
    val name: String,
    val type: String,
    val required: Boolean,
    val constraints: Constraints,
)

/** Union of all constraint keys seen in the API; [raw] keeps anything not modelled here. */
data class Constraints(
    val min: Double? = null,
    val max: Double? = null,
    val stepping: Double? = null,
    val enum: List<String>? = null,
    val regEx: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val sameDayAllowed: Boolean? = null,
    val efficientLowerBorder: Double? = null,
    val efficientUpperBorder: Double? = null,
    // Schedule constraints
    val modes: List<String>? = null,
    val maxEntries: Int? = null,
    val resolution: Int? = null,
    val defaultMode: String? = null,
    val overlapAllowed: Boolean? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
)
