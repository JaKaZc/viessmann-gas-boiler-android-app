package si.vicare.customapp.feature

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class MessageSource(val feature: String) {
    Error("device.messages.errors.raw"),
    Service("device.messages.service.raw"),
    Status("device.messages.status.raw"),
    Info("device.messages.info.raw"),
}

enum class MessageLevel { Error, Warning, Info }

/** One entry of a `device.messages.*.raw` feature, e.g. `{"errorCode":"S.29","timestamp":"…","priority":"status"}`. */
data class DeviceMessage(
    val source: MessageSource,
    val code: String,
    val timestampMillis: Long?,
    val priority: String?,
    val raw: JsonObject,
) {
    /** Stable identity used to notify only about messages not seen before. */
    val key: String get() = "${source.name}|$code|${timestampMillis ?: 0}"

    /**
     * Only faults matter (docs/step2-decisions.md): F. codes, the errors feature or an
     * error/fault priority are red; A. warnings are amber. Status (S.), service (P.) and
     * information (I.) codes are informational only: no banner, no notification.
     */
    val level: MessageLevel get() = when {
        code.startsWith("F.", ignoreCase = true) || source == MessageSource.Error ||
            priority?.lowercase() in ERROR_PRIORITIES -> MessageLevel.Error
        code.startsWith("A.", ignoreCase = true) -> MessageLevel.Warning
        else -> MessageLevel.Info
    }

    private companion object {
        val ERROR_PRIORITIES = setOf("error", "fault", "critical", "malfunction")
    }
}

object DeviceMessages {

    fun all(snapshot: FeatureSnapshot): List<DeviceMessage> = MessageSource.entries.flatMap { source ->
        val entries = snapshot[source.feature]?.properties?.get("entries") as? PropertyValue.ArrayValue ?: return@flatMap emptyList()
        entries.items.filterIsInstance<JsonObject>().mapNotNull { e ->
            val code = e.str("errorCode") ?: e.str("code") ?: return@mapNotNull null
            DeviceMessage(source, code, e.str("timestamp")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }, e.str("priority"), e)
        }
    }.sortedByDescending { it.timestampMillis ?: 0 }

    /** `device.lock.malfunction.active` – the boiler has locked out because of a fault. */
    fun malfunction(snapshot: FeatureSnapshot): Boolean = snapshot["device.lock.malfunction"]?.bool("active") == true

    /** Messages that warrant a banner: every fault, and warnings from the last [recentMillis]. */
    fun bannerMessages(snapshot: FeatureSnapshot, nowMillis: Long, recentMillis: Long = DAY): List<DeviceMessage> =
        all(snapshot).filter {
            it.level == MessageLevel.Error ||
                (it.level == MessageLevel.Warning && (it.timestampMillis ?: 0) >= nowMillis - recentMillis)
        }

    /** Messages in [current] that were not in [previous]; used for notifications. */
    fun newMessages(previous: FeatureSnapshot?, current: FeatureSnapshot): List<DeviceMessage> {
        if (previous == null) return emptyList() // first snapshot: nothing is "new" yet
        val seen = all(previous).map { it.key }.toSet()
        return all(current).filter { it.key !in seen && it.level != MessageLevel.Info }
    }

    private const val DAY = 24 * 60 * 60_000L
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
