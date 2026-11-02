package com.vigasboiler.app.widget

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.vigasboiler.app.data.HistoryRow
import com.vigasboiler.app.feature.FeatureParser
import com.vigasboiler.app.feature.FeatureSnapshot
import com.vigasboiler.app.feature.Fixtures

class StatsWidgetDataTest {

    private val zone = ZoneId.of("Europe/Ljubljana")
    private val today = LocalDate.of(2026, 11, 9)
    private val snapshot = FeatureSnapshot(Fixtures.boilerFeatures, FeatureParser.parse(Fixtures.read("06_gateway_features_i0_g0.json")), 1L)

    private fun at(day: LocalDate, hour: Int) = ZonedDateTime.of(day.atTime(hour, 0), zone).toInstant().toEpochMilli()
    private fun row(t: Long, feature: String, property: String, v: Double) = HistoryRow(t, feature, property, v, null)

    @Test
    fun `counters come from the snapshot`() {
        val d = StatsWidgetData.from(snapshot, StatsWidgetData.History(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()), 10.55, zone, today)
        assertEquals(5.4, d.gasToday!!, 0.0)
        assertEquals(29.5, d.gas7Days!!, 0.0)
        assertEquals(100.4, d.gasYear!!, 0.0)
        assertEquals(61.0, d.heatToday!!, 0.0)
        assertEquals(0.9, d.powerToday!!, 0.0)
        assertEquals(1011.7 / (100.4 * 10.55), d.efficiency!!, 1e-9)
        assertEquals(8008.0, d.totalHours!!, 0.0)
        assertNull("last month is 0 in the sample: no comparison", d.monthChangePercent)
        assertNull(d.runningShareToday)
    }

    @Test
    fun `today's running share and modulation come from the history`() {
        val active = (0 until 8).map { h -> row(at(today, h), "heating.burners.0", "active", if (h % 2 == 0) 1.0 else 0.0) }
        val modulation = (0 until 8).map { h -> row(at(today, h), "heating.burners.0.modulation", "value", if (h % 2 == 0) 20.0 + h else 0.0) }
        val yesterday = row(at(today.minusDays(1), 12), "heating.burners.0", "active", 1.0) // not today
        val d = StatsWidgetData.from(snapshot, StatsWidgetData.History(emptyList(), emptyList(), active + yesterday, modulation, emptyList(), emptyList()), 10.55, zone, today)
        assertEquals(0.5, d.runningShareToday!!, 1e-9)
        assertEquals(23.0, d.avgModulationToday!!, 1e-9) // 20, 22, 24, 26
    }

    @Test
    fun `starts per hour and month change are derived`() {
        val d = StatsWidgetData.from(snapshot, StatsWidgetData.History(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()), 10.55, zone, today)
            .copy(avgHours7 = 4.0, avgStarts7 = 8.0, gasMonth = 60.0, gasLastMonth = 50.0)
        assertEquals(2.0, d.startsPerHour7!!, 1e-9)
        assertEquals(20.0, d.monthChangePercent!!, 1e-9)
    }
}
