package com.vigasboiler.app.history

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.vigasboiler.app.data.HistoryRow
import com.vigasboiler.app.feature.FeatureSnapshot
import com.vigasboiler.app.feature.Fixtures
import com.vigasboiler.app.feature.HeatingCurve

class HistoryTest {

    private val zone = ZoneId.of("Europe/Ljubljana")
    private fun at(y: Int, m: Int, d: Int, h: Int) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun row(millis: Long, value: Double) = HistoryRow(millis, "heating.gas.consumption.summary.heating", "currentDay", value, "cubicMeter")

    @Test
    fun `snapshot values include numbers and booleans of enabled features only`() {
        val values = HistoryValues.extract(FeatureSnapshot(Fixtures.boilerFeatures, emptyList(), 0))
        assertEquals(94, values.size)
        assertTrue(values.none { it.feature.endsWith("houseLocation") })
        assertTrue(values.any { it.feature == "heating.sensors.temperature.outside" && it.property == "value" && it.unit == "celsius" })
        assertTrue(values.any { it.feature == "heating.burners.0" && it.property == "active" && it.value == 1.0 })
        assertTrue(values.none { it.feature.startsWith("heating.circuits.1") }) // disabled
    }

    @Test
    fun `daily totals are the highest counter value of each local day`() {
        val rows = listOf(
            row(at(2026, 11, 1, 8), 1.0), row(at(2026, 11, 1, 23), 5.4),
            row(at(2026, 11, 2, 0), 0.1), row(at(2026, 11, 2, 12), 2.0),
        )
        assertEquals(
            listOf(PeriodValue(LocalDate.of(2026, 11, 1), 5.4), PeriodValue(LocalDate.of(2026, 11, 2), 2.0)),
            ConsumptionHistory.daily(rows, zone),
        )
    }

    @Test
    fun `weekly sums the days of an ISO week`() {
        // 2026-11-01 is a Sunday (week of Mon 26 Oct); 2026-11-02 is Monday.
        val rows = listOf(row(at(2026, 10, 31, 20), 3.0), row(at(2026, 11, 1, 20), 4.0), row(at(2026, 11, 2, 20), 2.5))
        assertEquals(
            listOf(PeriodValue(LocalDate.of(2026, 10, 26), 7.0), PeriodValue(LocalDate.of(2026, 11, 2), 2.5)),
            ConsumptionHistory.weekly(rows, zone),
        )
    }

    @Test
    fun `monthly and yearly use their own counters`() {
        val month = listOf(row(at(2026, 10, 30, 12), 80.0), row(at(2026, 10, 31, 23), 95.0), row(at(2026, 11, 1, 12), 5.4))
        assertEquals(
            listOf(PeriodValue(LocalDate.of(2026, 10, 1), 95.0), PeriodValue(LocalDate.of(2026, 11, 1), 5.4)),
            ConsumptionHistory.monthly(month, zone),
        )
        assertEquals(listOf(PeriodValue(LocalDate.of(2026, 1, 1), 95.0)), ConsumptionHistory.yearly(month.take(2), zone))
    }

    @Test
    fun `buckets average values into integer indices`() {
        val start = at(2026, 11, 1, 0)
        val rows = listOf(row(start + 5 * 60_000, 1.0), row(start + 25 * 60_000, 3.0), row(start + 95 * 60_000, 7.0), row(start - 1, 99.0))
        assertEquals(listOf(0 to 2.0, 3 to 7.0), HistoryBuckets.bucket(rows, start, 30 * 60_000L))
    }

    @Test
    fun `outside temperature daily min avg max and summary`() {
        fun out(millis: Long, v: Double) = HistoryRow(millis, OutsideTemperature.FEATURE, "value", v, "celsius")
        val rows = listOf(
            out(at(2026, 10, 30, 6), -2.0), out(at(2026, 10, 30, 14), 8.0),
            out(at(2026, 10, 31, 6), 1.0), out(at(2026, 10, 31, 14), 11.0),
            out(at(2026, 11, 1, 6), 4.0), out(at(2026, 11, 1, 14), 12.0), out(at(2026, 11, 1, 20), 8.0),
            out(at(2026, 9, 1, 12), 25.0), // older than 30 days: ignored
        )
        val stats = OutsideTemperature.stats(rows, zone, LocalDate.of(2026, 11, 1))
        assertEquals(3, stats.days.size)
        assertEquals(DayRange(LocalDate.of(2026, 11, 1), 4.0, 8.0, 12.0), stats.today)
        assertEquals(LocalDate.of(2026, 10, 30), stats.coldest!!.date)
        assertEquals(LocalDate.of(2026, 11, 1), stats.warmest!!.date)
        assertEquals((3.0 + 6.0 + 8.0) / 3, stats.avg30!!, 1e-9)
    }

    @Test
    fun `burner hours and starts per day are counter increases since the previous day`() {
        fun hours(millis: Long, v: Double) = HistoryRow(millis, BurnerDaily.FEATURE, "hours", v, "hour")
        fun starts(millis: Long, v: Double) = HistoryRow(millis, BurnerDaily.FEATURE, "starts", v, "")
        val h = listOf(
            hours(at(2026, 10, 29, 12), 7990.0), // only baseline for the 30th
            hours(at(2026, 10, 30, 6), 7992.0), hours(at(2026, 10, 30, 23), 7998.0),
            hours(at(2026, 10, 31, 23), 8003.0),
            // 1 Nov missing: 2 Nov has no baseline and is left out.
            hours(at(2026, 11, 2, 23), 8010.0),
            hours(at(2026, 11, 3, 9), 8012.0),
        )
        val s = listOf(
            starts(at(2026, 10, 29, 12), 3600.0),
            starts(at(2026, 10, 30, 6), 3605.0), starts(at(2026, 10, 30, 23), 3611.0),
            starts(at(2026, 10, 31, 23), 3620.0),
            starts(at(2026, 11, 2, 23), 3640.0),
            starts(at(2026, 11, 3, 9), 3643.0),
        )
        val stats = BurnerDaily.stats(h, s, zone, LocalDate.of(2026, 11, 3))
        assertEquals(
            listOf(
                BurnerDay(LocalDate.of(2026, 10, 30), 8.0, 11.0),
                BurnerDay(LocalDate.of(2026, 10, 31), 5.0, 9.0),
                BurnerDay(LocalDate.of(2026, 11, 3), 2.0, 3.0),
            ),
            stats.days,
        )
        assertEquals(BurnerDay(LocalDate.of(2026, 11, 3), 2.0, 3.0), stats.today)
        assertEquals(null, stats.yesterday)
        // Today is still running and not part of the average.
        assertEquals(6.5, stats.avgHours7!!, 1e-9)
        assertEquals(10.0, stats.avgStarts7!!, 1e-9)
    }

    @Test
    fun `a counter that goes backwards gives no value for that day`() {
        fun hours(millis: Long, v: Double) = HistoryRow(millis, BurnerDaily.FEATURE, "hours", v, "hour")
        val rows = listOf(hours(at(2026, 11, 1, 20), 8000.0), hours(at(2026, 11, 2, 20), 3.0), hours(at(2026, 11, 3, 20), 7.0))
        assertEquals(mapOf(LocalDate.of(2026, 11, 3) to 4.0), BurnerDaily.perDay(rows, zone))
    }

    @Test
    fun `csv escapes commas and quotes`() {
        assertEquals(
            "2026-11-01T00:00:00Z,a,\"b,c\",1.5,celsius",
            HistoryCsv.line(HistoryRow(1793491200000, "a", "b,c", 1.5, "celsius")),
        )
    }

    @Test
    fun `heating curve matches the known curve shape`() {
        // Slope 1.4, shift 0, room 20 °C, outside −10 °C ≈ 63 °C supply.
        assertEquals(63.17, HeatingCurve.supplyTemperature(1.4, 0.0, 20.0, -10.0), 0.01)
        // At outside == room setpoint the supply equals the setpoint plus shift.
        assertEquals(23.0, HeatingCurve.supplyTemperature(1.4, 3.0, 20.0, 20.0), 1e-9)
        // Clamped to the supply limits.
        assertTrue(HeatingCurve.points(3.5, 40.0, 20.0, max = 60.0).all { it.second <= 60.0 })
    }
}
