package com.vigasboiler.app.widget

import com.vigasboiler.app.feature.DeviceMessages
import com.vigasboiler.app.feature.FeatureSnapshot
import com.vigasboiler.app.feature.MessageLevel
import com.vigasboiler.app.feature.gatewayStatus

/** Everything the home-screen widget shows, taken from the last snapshot. */
data class WidgetData(
    val room: Double?,
    val target: Double?,
    val outside: Double?,
    val mode: String?,
    val program: String?,
    val burnerOn: Boolean,
    val modulation: Double?,
    val supply: Double?,
    val pressure: Double?,
    /** Null when the gateway status is not reported. */
    val online: Boolean?,
    /** Wi-Fi signal of the gateway in dBm. */
    val wifiDbm: Double?,
    /** Newest fault code (F. or error priority), if any. */
    val fault: String?,
    val updatedAtMillis: Long,
    /** When the background refresh is expected to run next; never in the past. */
    val nextRefreshMillis: Long,
    /** Older than two refresh intervals: background refresh is not getting through. */
    val stale: Boolean,
) {
    companion object {
        fun from(snapshot: FeatureSnapshot, scheduledNextMillis: Long?, intervalMinutes: Int, nowMillis: Long): WidgetData {
            val intervalMillis = intervalMinutes * 60_000L
            val program = snapshot["heating.circuits.0.operating.programs.active"]?.text()
            return WidgetData(
                room = snapshot["heating.circuits.0.sensors.temperature.room"]?.number(),
                target = program?.let { snapshot["heating.circuits.0.operating.programs.$it"]?.number("temperature") },
                outside = snapshot["heating.sensors.temperature.outside"]?.number(),
                mode = snapshot["heating.circuits.0.operating.modes.active"]?.text(),
                program = program,
                burnerOn = snapshot["heating.burners.0"]?.bool("active") == true,
                modulation = snapshot["heating.burners.0.modulation"]?.number(),
                supply = snapshot["heating.boiler.sensors.temperature.commonSupply"]?.number(),
                pressure = snapshot["heating.sensors.pressure.supply"]?.number(),
                online = snapshot.gatewayStatus()?.equals("online", ignoreCase = true),
                wifiDbm = snapshot["tcu.wifi"]?.number("strength") ?: snapshot.gatewayFeature("gateway.wifi")?.number("strength"),
                fault = DeviceMessages.all(snapshot).firstOrNull { it.level == MessageLevel.Error }?.code,
                updatedAtMillis = snapshot.fetchedAtMillis,
                nextRefreshMillis = nextRefresh(scheduledNextMillis, snapshot.fetchedAtMillis, intervalMillis, nowMillis),
                stale = nowMillis - snapshot.fetchedAtMillis > 2 * intervalMillis + STALE_GRACE_MILLIS,
            )
        }

        /**
         * WorkManager's next run when it is in the future; otherwise (running right now, or not
         * scheduled yet) the last refresh plus one interval, moved forward past now.
         */
        fun nextRefresh(scheduledMillis: Long?, lastMillis: Long, intervalMillis: Long, nowMillis: Long): Long {
            if (scheduledMillis != null && scheduledMillis > nowMillis) return scheduledMillis
            var next = lastMillis + intervalMillis
            if (next <= nowMillis) next += ((nowMillis - next) / intervalMillis + 1) * intervalMillis
            return next
        }

        private const val STALE_GRACE_MILLIS = 5 * 60_000L
    }
}
