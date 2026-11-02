package com.vigasboiler.app.analysis

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.vigasboiler.app.analysis.HeatingCheck.Finding
import com.vigasboiler.app.analysis.HeatingCheck.Rating
import com.vigasboiler.app.analysis.HeatingCheck.Series
import com.vigasboiler.app.data.HistoryRow

class HeatingCheckTest {

    private val zone = ZoneId.of("Europe/Ljubljana")
    private val now = ZonedDateTime.of(2026, 11, 9, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val step = 30 * 60_000L
    /** One week of snapshots every 30 minutes, ending now. */
    private val times = (0 until 336).map { now - (335 - it) * step }
    private val settings = HeatingCheck.Settings(slope = 1.4, shift = 0.0, normal = 21.0, reduced = 19.0, comfort = 22.0, pressureMin = 1.0, pressureMax = 2.5, intervalMinutes = 30)

    private fun rows(series: Series, value: (Int) -> Double?) =
        times.mapIndexedNotNull { i, t -> value(i)?.let { HistoryRow(t, series.feature, series.property, it, null) } }

    /** Normal all the time unless [reduced] says otherwise; room and outside from the lambdas. */
    private fun week(
        room: (Int) -> Double? = { 21.0 },
        outside: (Int) -> Double = { 3.0 },
        burnerOn: (Int) -> Boolean = { it % 2 == 0 },
        supply: (Int) -> Double = { 45.0 },
        reduced: (Int) -> Boolean = { false },
    ) = mapOf(
        Series.BurnerActive to rows(Series.BurnerActive) { if (burnerOn(it)) 1.0 else 0.0 },
        Series.Modulation to rows(Series.Modulation) { if (burnerOn(it)) 35.0 else 0.0 },
        Series.Room to rows(Series.Room, room),
        Series.Outside to rows(Series.Outside) { outside(it) },
        Series.Supply to rows(Series.Supply) { supply(it) },
        Series.NormalActive to rows(Series.NormalActive) { if (reduced(it)) 0.0 else 1.0 },
        Series.NormalTemp to rows(Series.NormalTemp) { 21.0 },
        Series.ReducedActive to rows(Series.ReducedActive) { if (reduced(it)) 1.0 else 0.0 },
        Series.ComfortActive to rows(Series.ComfortActive) { 0.0 },
    )

    @Test
    fun `a day counts with 12 snapshots and the check needs 7 of them`() {
        val today = LocalDate.of(2026, 11, 9)
        fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val sixDays = (0L..5L).flatMap { d -> (0 until 12).map { h -> at(today.minusDays(d), h) } }
        assertEquals(HeatingCheck.Readiness(6), HeatingCheck.readiness(sixDays, zone, today))
        val thin = sixDays + (0 until 11).map { at(today.minusDays(6), it) }
        assertEquals(6, HeatingCheck.readiness(thin, zone, today).days)
        val ready = sixDays + (0 until 12).map { at(today.minusDays(6), it) }
        assertTrue(HeatingCheck.readiness(ready, zone, today).ready)
        // Older than two weeks does not count.
        val old = (0 until 12).map { at(today.minusDays(20), it) }
        assertEquals(6, HeatingCheck.readiness(sixDays + old, zone, today).days)
        assertEquals(1, HeatingCheck.readiness(sixDays, zone, today).missing)
    }

    @Test
    fun `starts per burner hour decide short cycling`() {
        fun counters(hours: Double, starts: Double) =
            HeatingCheck.cycling(rows(Series.Hours) { 8000 + hours * it / 335 }, rows(Series.Starts) { 3600 + starts * it / 335 })
        assertEquals(Rating.Good, counters(35.0, 35.0)!!.rating)
        assertEquals(Rating.Tip, counters(35.0, 70.0)!!.rating)
        val bad = counters(35.0, 140.0)!!
        assertEquals(Rating.Warning, bad.rating)
        assertEquals(4.0, bad.startsPerHour, 1e-9)
        assertNull("too little burner time to judge", counters(2.0, 10.0))
        // A counter that jumps back (new control unit) does not count as negative starts.
        val jump = HeatingCheck.cycling(
            rows(Series.Hours) { 8000 + 35.0 * it / 335 },
            rows(Series.Starts) { if (it < 200) 3600 + 0.2 * it else 10 + 0.2 * (it - 200) },
        )!!
        assertEquals(66.8 / 35, jump.startsPerHour, 0.01) // 1.9 per hour, not a huge negative or positive jump
        assertEquals(Rating.Tip, jump.rating)
    }

    @Test
    fun `rooms colder than the target suggest a higher shift`() {
        val f = HeatingCheck.roomVsTarget(week(room = { 19.8 }), settings) as Finding.RoomVsTarget
        assertEquals(Rating.Warning, f.rating)
        assertEquals(-1.2, f.deviation, 1e-9)
        assertEquals(HeatingCheck.CurveChange(1.4, 1.4, 0.0, 2.0), f.curve)
    }

    @Test
    fun `rooms colder only in cold weather suggest a steeper slope`() {
        val f = HeatingCheck.roomVsTarget(
            week(outside = { if (it < 168) -2.0 else 10.0 }, room = { if (it < 168) 20.0 else 21.1 }),
            settings,
        ) as Finding.RoomVsTarget
        assertEquals(Rating.Tip, f.rating)
        assertEquals(1.5, f.curve!!.slopeTo, 1e-9)
        assertEquals(0.0, f.curve!!.shiftTo, 1e-9)
    }

    @Test
    fun `rooms on target need no change and warm-up after a switch is ignored`() {
        // Reduced every night 22–06; right after switching back the room is still cold.
        val reduced = { i: Int -> (i % 48) !in 12..43 }
        val room = { i: Int -> if (!reduced(i) && (i % 48) in 12..14) 19.0 else 21.1 }
        val f = HeatingCheck.roomVsTarget(week(room = room, reduced = reduced), settings) as Finding.RoomVsTarget
        assertEquals(Rating.Good, f.rating)
        assertNull(f.curve)
    }

    @Test
    fun `without a room sensor the comparison is skipped`() {
        assertEquals(Finding.NoRoomSensor, HeatingCheck.roomVsTarget(week(room = { null }), settings))
    }

    @Test
    fun `setback of none or more than 4 degrees is a tip`() {
        assertEquals(Rating.Tip, HeatingCheck.setback(settings.copy(reduced = 21.0), emptyMap())!!.rating)
        assertEquals(Rating.Tip, HeatingCheck.setback(settings.copy(reduced = 16.0), emptyMap())!!.rating)
        assertEquals(Rating.Good, HeatingCheck.setback(settings, emptyMap())!!.rating)
    }

    @Test
    fun `comfort below normal is pointed out`() {
        assertNull(HeatingCheck.comfortOrder(settings))
        assertEquals(Rating.Info, HeatingCheck.comfortOrder(settings.copy(comfort = 20.0))!!.rating)
    }

    @Test
    fun `supply temperature counts only while the burner runs`() {
        val data = week(supply = { if (it % 2 == 0) 65.0 else 30.0 })
        val on = data.getValue(Series.BurnerActive).associate { it.timestampMillis to it.value }
        val f = HeatingCheck.supply(on, data.getValue(Series.Supply))!!
        assertEquals(65.0, f.average, 1e-9)
        assertEquals(Rating.Warning, f.rating)
        val cool = week(supply = { 45.0 })
        assertEquals(Rating.Good, HeatingCheck.supply(on, cool.getValue(Series.Supply))!!.rating)
    }

    @Test
    fun `burner running on mild days is a tip`() {
        val data = week(outside = { if (it < 100) 18.0 else 5.0 }, burnerOn = { true })
        val on = data.getValue(Series.BurnerActive).associate { it.timestampMillis to it.value }
        assertEquals(Rating.Tip, HeatingCheck.heatingLimit(on, data.getValue(Series.Outside))!!.rating)
        assertNull("no mild days, nothing to say", HeatingCheck.heatingLimit(on, week().getValue(Series.Outside)))
    }

    @Test
    fun `pressure out of limits is a warning, a slow drop a tip`() {
        assertEquals(Rating.Good, HeatingCheck.pressure(rows(Series.Pressure) { 1.5 }, settings)!!.rating)
        val drop = HeatingCheck.pressure(rows(Series.Pressure) { 1.6 - 0.3 * it / 335 }, settings)!!
        assertEquals(Rating.Tip, drop.rating)
        assertTrue(drop.drop > 0.2)
        assertEquals(Rating.Warning, HeatingCheck.pressure(rows(Series.Pressure) { if (it == 200) 0.8 else 1.5 }, settings)!!.rating)
    }

    @Test
    fun `gas is the sum of each day's highest counter and is normalised by degree days`() {
        // currentDay grows from 0 to 4 m³ each day; outside 10 °C means 10 degree days per day.
        val gas = rows(Series.GasToday) { i -> ((times[i] - times.first()) % (24 * 3_600_000L)).toDouble() / (24 * 3_600_000L) * 4 }
        val f = HeatingCheck.gas(gas, rows(Series.Outside) { 10.0 }, zone)!!
        assertTrue(f.cubicMeters > 20)
        assertNotNull(f.perDegreeDay)
        assertEquals(0.4, f.perDegreeDay!!, 0.1)
    }

    @Test
    fun `missed background refreshes lower the coverage`() {
        assertEquals(Rating.Good, HeatingCheck.coverage(336, 30).rating)
        assertEquals(Rating.Tip, HeatingCheck.coverage(150, 30).rating)
        assertEquals(1.0, HeatingCheck.coverage(500, 30).share, 1e-9) // manual refreshes on top
    }

    @Test
    fun `the report groups findings and ignores data outside the week`() {
        val data = week() + mapOf(
            Series.Hours to rows(Series.Hours) { 8000 + 35.0 * it / 335 },
            Series.Starts to rows(Series.Starts) { 3600 + 35.0 * it / 335 },
            Series.Pressure to rows(Series.Pressure) { 1.5 },
            Series.Room to (week().getValue(Series.Room) + HistoryRow(now - 30L * 24 * 3_600_000, Series.Room.feature, Series.Room.property, 5.0, null)),
        )
        val report = HeatingCheck.analyze(data, settings, zone, now)
        assertEquals(336, report.samples)
        assertTrue(report.sections.getValue(HeatingCheck.Section.Burner).any { it is Finding.Cycling })
        val room = report.sections.getValue(HeatingCheck.Section.Comfort).filterIsInstance<Finding.RoomVsTarget>().single()
        assertEquals("the 30-day-old cold reading is outside the window", Rating.Good, room.rating)
        assertTrue(report.sections.getValue(HeatingCheck.Section.System).any { it is Finding.Coverage })
        assertEquals(0, report.count(Rating.Warning))
    }
}
