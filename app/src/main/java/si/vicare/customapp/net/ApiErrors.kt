package si.vicare.customapp.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Turns a Viessmann error body (`{"statusCode":400,"errorType":"…","message":"…"}`) into one readable line. */
object ApiErrors {
    fun readable(httpCode: Int, body: String?): String {
        val json = body?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        if (json == null) return listOfNotNull("HTTP $httpCode", body?.take(300)?.takeIf { it.isNotBlank() }).joinToString(": ")
        val type = json.str("errorType")
        val message = json.str("message")
        val reason = (json["extendedPayload"] as? JsonObject)?.let { it.str("reason") ?: it.str("details") ?: it.str("code") }
        val parts = listOfNotNull(type, message, reason).distinct()
        return "HTTP $httpCode" + if (parts.isEmpty()) "" else ": " + parts.joinToString(" · ")
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
