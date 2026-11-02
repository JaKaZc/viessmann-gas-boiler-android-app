package com.vigasboiler.app.work

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.vigasboiler.app.feature.DeviceMessage
import com.vigasboiler.app.feature.DeviceMessages
import com.vigasboiler.app.feature.FeatureSnapshot
import com.vigasboiler.app.feature.MessageLevel
import com.vigasboiler.app.feature.PropertyValue
import com.vigasboiler.app.ratelimit.RateBudget
import com.vigasboiler.app.settings.AppSettings

/** Persisted between runs so each condition notifies once, not on every refresh. */
@Serializable
data class NotifierState(
    val offlineSinceMillis: Long? = null,
    val offlineNotified: Boolean = false,
    val pressureAlerted: Boolean = false,
    val budgetAlerted: Boolean = false,
    val malfunctionAlerted: Boolean = false,
)

sealed interface Alert {
    data class NewMessage(val message: DeviceMessage) : Alert
    data object Malfunction : Alert
    data class Pressure(val bar: Double, val min: Double, val max: Double) : Alert
    data class GatewayOffline(val sinceMillis: Long) : Alert
    data class BudgetLow(val budget: RateBudget) : Alert
}

/** Pure decision logic for notifications; [Notifier] posts the results. */
object NotificationRules {

    fun onSnapshot(previous: FeatureSnapshot?, current: FeatureSnapshot, settings: AppSettings, state: NotifierState, now: Long): Pair<List<Alert>, NotifierState> {
        val alerts = mutableListOf<Alert>()
        var next = state

        DeviceMessages.newMessages(previous, current)
            .filter { it.level == MessageLevel.Error || it.level == MessageLevel.Warning }
            .forEach { alerts += Alert.NewMessage(it) }

        val malfunction = DeviceMessages.malfunction(current)
        if (malfunction && !state.malfunctionAlerted) alerts += Alert.Malfunction
        next = next.copy(malfunctionAlerted = malfunction)

        current["heating.sensors.pressure.supply"]?.number()?.let { bar ->
            val out = bar < settings.pressureMinBar || bar > settings.pressureMaxBar
            if (out && !state.pressureAlerted) alerts += Alert.Pressure(bar, settings.pressureMinBar, settings.pressureMaxBar)
            next = next.copy(pressureAlerted = out)
        }

        when (gatewayOnline(current)) {
            true -> next = next.copy(offlineSinceMillis = null, offlineNotified = false)
            false -> next = offline(next, settings, now, alerts)
            null -> Unit
        }
        return alerts to next
    }

    /** A refresh that could not reach the device counts as an offline observation. */
    fun onUnreachable(settings: AppSettings, state: NotifierState, now: Long): Pair<List<Alert>, NotifierState> {
        val alerts = mutableListOf<Alert>()
        return alerts to offline(state, settings, now, alerts)
    }

    fun onBudget(budget: RateBudget, state: NotifierState): Pair<List<Alert>, NotifierState> {
        val low = budget.isSoftLimited || budget.block != null
        val alerts = if (low && !state.budgetAlerted) listOf(Alert.BudgetLow(budget)) else emptyList()
        return alerts to state.copy(budgetAlerted = low)
    }

    private fun offline(state: NotifierState, settings: AppSettings, now: Long, alerts: MutableList<Alert>): NotifierState {
        val since = state.offlineSinceMillis ?: now
        val due = now - since >= settings.offlineAlertMinutes * 60_000L
        if (due && !state.offlineNotified) alerts += Alert.GatewayOffline(since)
        return state.copy(offlineSinceMillis = since, offlineNotified = state.offlineNotified || due)
    }

    /** From `gateway.devices`: true/false when the gateway reports a status, null when unknown. */
    fun gatewayOnline(s: FeatureSnapshot): Boolean? {
        val list = (s.gatewayFeature("gateway.devices")?.properties?.get("devices") as? PropertyValue.ObjectValue)?.value as? JsonArray ?: return null
        val gateway = list.filterIsInstance<JsonObject>().firstOrNull { (it["type"] as? JsonPrimitive)?.content == "tcu" }
            ?: list.firstOrNull() as? JsonObject ?: return null
        val status = (gateway["status"] as? JsonPrimitive)?.content ?: return null
        return status.equals("online", ignoreCase = true)
    }
}
