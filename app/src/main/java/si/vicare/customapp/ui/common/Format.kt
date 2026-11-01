package si.vicare.customapp.ui.common

import java.text.DateFormat
import java.text.NumberFormat
import java.time.Instant
import java.util.Date
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import si.vicare.customapp.feature.PropertyValue

/** Short symbols for the units the API uses. Unknown units are shown as they come. */
fun unitSymbol(unit: String?): String = when (unit) {
    null, "" -> ""
    "celsius" -> "°C"
    "kelvin" -> "K"
    "percent" -> "%"
    "bar" -> "bar"
    "cubicMeter" -> "m³"
    "kilowattHour" -> "kWh"
    "wattHour" -> "Wh"
    "watt" -> "W"
    "kilowatt" -> "kW"
    "hour" -> "h"
    "minute" -> "min"
    "second" -> "s"
    "liter" -> "L"
    "degree" -> "°"
    "kilogram" -> "kg"
    else -> unit
}

fun formatNumber(value: Double, maxDecimals: Int = 1): String =
    NumberFormat.getNumberInstance().apply {
        maximumFractionDigits = maxDecimals
        minimumFractionDigits = 0
    }.format(value)

fun formatWithUnit(value: Double?, unit: String?, maxDecimals: Int = 1): String {
    if (value == null) return "–"
    val symbol = unitSymbol(unit)
    return if (symbol.isEmpty()) formatNumber(value, maxDecimals)
    else if (symbol == "%" || symbol.startsWith("°")) formatNumber(value, maxDecimals) + symbol
    else formatNumber(value, maxDecimals) + " " + symbol
}

private val pretty = Json { prettyPrint = true }

fun prettyJson(element: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), element)

/** One-line text for any property value. Structured values are summarised. */
fun formatProperty(value: PropertyValue): String = when (value) {
    is PropertyValue.Number -> formatWithUnit(value.value, value.unit, maxDecimals = 3)
    is PropertyValue.Text -> value.value.ifEmpty { "\"\"" }
    is PropertyValue.Bool -> value.value.toString()
    is PropertyValue.ArrayValue -> "[${value.items.size}]"
    is PropertyValue.ObjectValue -> value.type
    is PropertyValue.Unknown -> "${value.type}: ${value.raw.toString().take(60)}"
}

fun formatDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

fun formatTime(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

/** API timestamps are ISO-8601 (`2026-11-01T12:37:41.000Z`). */
fun isoToMillis(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
