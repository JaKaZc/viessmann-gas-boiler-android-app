package com.vigasboiler.app.analysis

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.vigasboiler.app.feature.FeatureRepository
import com.vigasboiler.app.history.HistoryRepository
import com.vigasboiler.app.settings.SettingsRepository

/** Loads the week of history and the current settings for [HeatingCheck]. No API calls. */
class HeatingCheckRepository(
    private val history: HistoryRepository,
    private val features: FeatureRepository,
    private val settings: SettingsRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** How many of the required days are collected; every snapshot stores the burner state. */
    val readiness: Flow<HeatingCheck.Readiness> =
        history.series(HeatingCheck.Series.BurnerActive.feature, HeatingCheck.Series.BurnerActive.property, clock() - 15 * DAY)
            .map { rows -> HeatingCheck.readiness(rows.map { it.timestampMillis }, zone, LocalDate.now(zone)) }
            .flowOn(Dispatchers.Default)

    suspend fun run(): HeatingCheck.Report = withContext(Dispatchers.Default) {
        val now = clock()
        val since = now - (HeatingCheck.WINDOW_DAYS + 1) * DAY
        val data = HeatingCheck.Series.entries.associateWith { history.series(it.feature, it.property, since).first() }
        val app = settings.current()
        val snapshot = features.snapshot.value
        fun number(feature: String, property: String) = snapshot?.get(feature)?.number(property)
        HeatingCheck.analyze(
            data,
            HeatingCheck.Settings(
                slope = number("heating.circuits.0.heating.curve", "slope"),
                shift = number("heating.circuits.0.heating.curve", "shift"),
                normal = number("heating.circuits.0.operating.programs.normal", "temperature"),
                reduced = number("heating.circuits.0.operating.programs.reduced", "temperature"),
                comfort = number("heating.circuits.0.operating.programs.comfort", "temperature"),
                pressureMin = app.pressureMinBar,
                pressureMax = app.pressureMaxBar,
                intervalMinutes = app.backgroundIntervalMinutes,
            ),
            zone,
            now,
        )
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
