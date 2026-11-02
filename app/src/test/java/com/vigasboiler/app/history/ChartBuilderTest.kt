package com.vigasboiler.app.history

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.vigasboiler.app.data.HistoryRow
import com.vigasboiler.app.history.ChartBuilder.Source

class ChartBuilderTest {

    private val zone = ZoneId.of("Europe/Ljubljana")
    private val day = LocalDate.of(2026, 11, 9)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = ZonedDateTime.of(d.atTime(h, m), zone).toInstant().toEpochMilli()
    private fun row(s: Source, t: Long, v: Double) = HistoryRow(t, s.feature, s.property, v, null)

    @Test
    fun `gas per day survives the counter reset at midnight`() {
        val rows = listOf(
            row(Source.GasToday, at(day.minusDays(2), 23), 4.0), // before the range: baseline only
            row(Source.GasToday, at(day.minusDays(1), 0, 30), 0.2), // reset: 0.2 belongs to yesterday
            row(Source.GasToday, at(day.minusDays(1), 23), 5.0),
            row(Source.GasToday, at(day, 1), 0.5),
            row(Source.GasToday, at(day, 11), 2.0),
        )
        val now = at(day, 12)
        val chart = ChartBuilder.gas(ChartRange.Week, rows, now - 7 * 24 * 3_600_000L, zone)
        val perDay = chart.series.single().points.toMap()
        assertEquals(5.0, perDay.getValue(6), 1e-9) // yesterday: 0.2 + 4.8
        assertEquals(2.0, perDay.getValue(7), 1e-9) // today: 0.5 + 1.5
        assertEquals(setOf(6, 7), perDay.keys) // the baseline before the range adds no bar
        assertEquals(7.0, chart.stats!!.total!!, 1e-9)
    }

    @Test
    fun `gas per hour for the last 24 hours`() {
        val now = at(day, 12)
        val rows = listOf(row(Source.GasToday, at(day, 9), 1.0), row(Source.GasToday, at(day, 9, 30), 1.3), row(Source.GasToday, at(day, 10, 15), 1.9))
        val chart = ChartBuilder.gas(ChartRange.Day, rows, now - 24 * 3_600_000L, zone)
        val perHour = chart.series.single().points.toMap()
        assertEquals(3_600_000L, chart.bucketMillis)
        assertEquals(0.3, perHour.getValue(21), 1e-9) // 09:00–10:00 is the 21st hour after 12:00 yesterday
        assertEquals(0.6, perHour.getValue(22), 1e-9)
    }

    @Test
    fun `burner minutes per hour come from the on and off snapshots`() {
        val now = at(day, 12)
        val active = listOf(0, 10, 20, 30, 40, 50).map { m -> row(Source.BurnerActive, at(day, 10, m), if (m < 30) 1.0 else 0.0) }
        val chart = ChartBuilder.burner(ChartRange.Day, active, emptyList(), now - 24 * 3_600_000L, zone)
        assertEquals(30.0, chart.series.single().points.single().second, 1e-9)
    }

    @Test
    fun `burner hours per day come from the hour counter`() {
        val now = at(day, 12)
        val hours = listOf(row(Source.BurnerHours, at(day.minusDays(1), 23), 100.0), row(Source.BurnerHours, at(day, 6), 103.0), row(Source.BurnerHours, at(day, 11), 105.0))
        val chart = ChartBuilder.burner(ChartRange.Week, emptyList(), hours, now - 7 * 24 * 3_600_000L, zone)
        assertEquals(5.0, chart.series.single().points.last().second, 1e-9)
    }

    @Test
    fun `the target follows the active program`() {
        val t1 = at(day, 6); val t2 = at(day, 22)
        val data = mapOf(
            Source.NormalActive to listOf(row(Source.NormalActive, t1, 1.0), row(Source.NormalActive, t2, 0.0)),
            Source.NormalTemp to listOf(row(Source.NormalTemp, t1, 21.0), row(Source.NormalTemp, t2, 21.0)),
            Source.ReducedActive to listOf(row(Source.ReducedActive, t1, 0.0), row(Source.ReducedActive, t2, 1.0)),
            Source.ReducedTemp to listOf(row(Source.ReducedTemp, t1, 19.0), row(Source.ReducedTemp, t2, 19.0)),
        )
        assertEquals(listOf(21.0, 19.0), ChartBuilder.target(data, 0, Long.MAX_VALUE).map { it.value })
    }

    @Test
    fun `without a room sensor the temperature chart shows outside only, and empty charts stay empty`() {
        val now = at(day, 12)
        val outside = (0 until 48).map { row(Source.Outside, now - it * 1_800_000L, 5.0 + it % 3) }
        val set = ChartBuilder.build(ChartRange.Day, now, mapOf(Source.Outside to outside), zone)
        val temps = set[ChartKind.Temperatures]!!
        assertEquals(listOf(SeriesRole.Outside), temps.series.map { it.role })
        assertEquals(5.0, temps.stats!!.min, 1e-9)
        assertTrue(set[ChartKind.Pressure]!!.isEmpty)
        assertNull(set[ChartKind.Pressure]!!.stats)
    }
}
