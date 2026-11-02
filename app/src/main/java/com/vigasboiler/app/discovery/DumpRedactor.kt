package com.vigasboiler.app.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Masks identifiers and personal data across a whole dump so files can go into the repository.
 *
 * - Identifiers (installation IDs, gateway and device serials, equipment numbers, user IDs) are
 *   replaced by stable placeholders everywhere they occur, including inside command URIs, so the
 *   structure stays consistent between files.
 * - Personal fields (names, e-mail, phone, address, coordinates) and everything nested under them
 *   become `REDACTED`, and their values are also removed wherever else they appear.
 * - A personal document (the users/me response) is masked completely, keeping only its structure.
 *
 * This is a safety net, not a guarantee: review the output before committing it.
 */
class DumpRedactor {

    private val placeholders = LinkedHashMap<String, String>()
    private val counters = HashMap<String, Int>()

    /** First pass: call for every file so identifiers are known before any file is rewritten. */
    fun collect(json: String, personalDocument: Boolean = false) {
        val root = parse(json) ?: return
        collectFrom(root, key = null, parentKey = null, featureName = null, personal = personalDocument)
    }

    /** Second pass: returns the redacted, pretty-printed JSON (or a plain-text fallback). */
    fun redact(json: String, personalDocument: Boolean = false): String {
        val root = parse(json) ?: return redactText(json)
        return prettyJson.encodeToString(JsonElement.serializer(), redactElement(root, key = null, personal = personalDocument))
    }

    private fun collectFrom(element: JsonElement, key: String?, parentKey: String?, featureName: String?, personal: Boolean) {
        val inPersonal = personal || (key != null && isPersonalKey(key))
        when (element) {
            is JsonObject -> {
                val feature = (element["feature"] as? JsonPrimitive)?.content ?: featureName
                element.forEach { (k, v) -> collectFrom(v, k, key, feature, inPersonal) }
            }
            is JsonArray -> element.forEach { collectFrom(it, key, parentKey, featureName, inPersonal) }
            is JsonNull -> Unit
            is JsonPrimitive -> {
                if (key == null) return
                idCategory(key, parentKey, featureName, element)?.let { register(element.content, it) }
                if (inPersonal && element.isString && !isKeptKey(key)) registerPersonal(element.content)
            }
        }
    }

    /** Decides whether a primitive under [key] is an identifier that must be masked globally. */
    private fun idCategory(key: String, parentKey: String?, featureName: String?, value: JsonPrimitive): String? {
        val k = key.lowercase()
        return when {
            k == "installationid" -> "INSTALLATION_ID"
            k == "gatewayserial" || k == "gatewayid" -> "GATEWAY_SERIAL"
            k.contains("serial") -> "SERIAL"
            k == "userid" || k == "accountid" || k == "uuid" -> "USER_ID"
            // `value` of features such as device.serial, heating.boiler.serial or device.etn
            k == "value" && featureName?.contains("serial", ignoreCase = true) == true -> "SERIAL"
            k == "value" && value.isString && LONG_NUMBER.matches(value.content) -> "SERIAL"
            // `id` is only an identifier when it is long enough; device IDs like "0" are kept.
            k == "id" && parentKey != "properties" -> "ID"
            else -> null
        }
    }

    private fun register(value: String, category: String) {
        // Real identifiers always contain digits; this keeps device IDs such as "gateway" readable.
        if (value.length < MIN_ID_LENGTH || value.none(Char::isDigit) || value in placeholders) return
        val n = (counters[category] ?: 0) + 1
        counters[category] = n
        placeholders[value] = "${category}_$n"
    }

    /**
     * Personal values are also scrubbed from other fields, e.g. a street repeated in `addressline1`.
     * Plain lowercase words ("number", "degree") are enum/schema vocabulary, never personal, and
     * scrubbing them globally would corrupt every feature that uses them.
     */
    private fun registerPersonal(value: String) {
        if (value.length < MIN_PERSONAL_LENGTH || PLAIN_WORD.matches(value) || value in placeholders) return
        placeholders[value] = REDACTED
    }

    private fun redactElement(element: JsonElement, key: String?, personal: Boolean): JsonElement {
        val inPersonal = personal || (key != null && isPersonalKey(key))
        return when (element) {
            is JsonObject -> JsonObject(element.mapValues { (k, v) -> redactElement(v, k, inPersonal) })
            is JsonArray -> JsonArray(element.map { redactElement(it, key, inPersonal) })
            is JsonNull -> element
            is JsonPrimitive -> when {
                inPersonal && !isKeptKey(key) && (element.isString || element.content.toDoubleOrNull() != null) ->
                    JsonPrimitive(REDACTED)
                element.content in placeholders -> JsonPrimitive(placeholders.getValue(element.content))
                element.isString -> JsonPrimitive(redactText(element.content))
                else -> element
            }
        }
    }

    private fun redactText(text: String): String =
        // Longest first so an ID that contains a shorter ID is replaced as a whole.
        placeholders.entries.sortedByDescending { it.key.length }
            .fold(text) { acc, (raw, placeholder) -> acc.replace(raw, placeholder) }
            .replace(EMAIL, "REDACTED_EMAIL")

    private fun parse(json: String): JsonElement? = runCatching { Json.parseToJsonElement(json) }.getOrNull()

    companion object {
        private const val REDACTED = "REDACTED"
        private const val MIN_ID_LENGTH = 5
        private const val MIN_PERSONAL_LENGTH = 4
        private val prettyJson = Json { prettyPrint = true }
        private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private val LONG_NUMBER = Regex("\\d{10,}")
        private val PLAIN_WORD = Regex("[a-z]+")

        private val PERSONAL_EXACT = setOf("city", "region", "description", "salutation", "mobile", "gender", "altitude")
        private val PERSONAL_FRAGMENTS = listOf(
            "firstname", "lastname", "familyname", "givenname",
            "email", "login", "username", "phone", "fax",
            "address", "street", "housenumber", "zip", "postal", "building",
            "latitude", "longitude", "company",
        )

        /**
         * Kept even inside personal objects: locale fields useful for schedules, and the `type` /
         * `unit` schema fields of feature properties such as houseLocation.latitude.
         */
        private val KEPT_KEYS = setOf(
            "country", "countrycode", "timezone", "languagecode", "locale", "userstate", "type", "unit",
            "busaddress", // internal device bus number in status messages, not a postal address
        )

        fun isPersonalKey(key: String): Boolean {
            val k = key.lowercase()
            return k in PERSONAL_EXACT || PERSONAL_FRAGMENTS.any { k.contains(it) }
        }

        private fun isKeptKey(key: String?): Boolean = key != null && key.lowercase() in KEPT_KEYS
    }
}
