package com.vigasboiler.app.history

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.vigasboiler.app.analysis.HeatingCheck
import com.vigasboiler.app.feature.FeatureSnapshot
import com.vigasboiler.app.feature.Fixtures

class DemoHistoryTest {

    private val zone = ZoneId.of("Europe/Ljubljana")
    private val now = Instant.parse("2026-11-09T13:30:00Z").toEpochMilli()
    private val snapshot = FeatureSnapshot(Fixtures.boilerFeatures, Fixtures.gatewayFeatures, now)
    private val week = DemoHistory.week(snapshot, zone)
    private fun series(feature: String, property: String) =
        week.filter { it.value.feature == feature && it.value.property == property }.sortedBy { it.timestampMillis }

    @Test
    fun `the week is enough for the heating check and ends before the snapshot`() {
        val times = week.map { it.timestampMillis }.distinct()
        assertEquals(DemoHistory.STEPS, times.size)
        assertTrue(times.all { it < now })
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        assertTrue(HeatingCheck.readiness(times, zone, today).ready)
    }

    @Test
    fun `counters rise into the snapshot's values`() {
        val stats = snapshot["heating.burners.0.statistics"]!!
        val hours = series("heating.burners.0.statistics", "hours").map { it.value.value }
        val starts = series("heating.burners.0.statistics", "starts").map { it.value.value }
        assertTrue(hours.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(starts.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(hours.last() <= stats.number("hours")!!)
        assertTrue(starts.last() <= stats.number("starts")!!)
        // About a burner start per hour: a believable week, not a flat line.
        assertTrue(starts.last() - starts.first() in 100.0..250.0)
    }

    @Test
    fun `gas today restarts at midnight and stays below the snapshot's value`() {
        val gas = series("heating.gas.consumption.summary.heating", "currentDay")
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val byDay = gas.groupBy { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() }
        byDay.values.forEach { day -> assertTrue(day.map { it.value.value }.zipWithNext().all { (a, b) -> b >= a }) }
        val todayEnd = snapshot["heating.gas.consumption.summary.heating"]!!.number("currentDay")!!
        assertTrue(byDay.getValue(today).last().value.value <= todayEnd)
    }
}
