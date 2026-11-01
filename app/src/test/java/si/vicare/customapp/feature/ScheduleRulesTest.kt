package si.vicare.customapp.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.vicare.customapp.feature.ScheduleViolation.Kind

class ScheduleRulesTest {

    private val rules = ScheduleRules.from(
        Fixtures.command("heating.circuits.0.heating.schedule", "setSchedule").params.getValue("newSchedule").constraints,
    )
    private val real = Schedule.fromJson(
        (Fixtures.boiler("heating.circuits.0.heating.schedule").properties["entries"] as PropertyValue.ObjectValue).value,
    )!!

    private fun violations(vararg entries: ScheduleEntry) = rules.validateDay(Day.MON, entries.toList()).map { it.kind }

    @Test
    fun `rules come from the real constraints`() {
        assertEquals(ScheduleRules(listOf("normal", "comfort"), 2, 10, false, "reduced"), rules)
    }

    @Test
    fun `real schedule is valid and round trips`() {
        assertEquals(emptyList<ScheduleViolation>(), rules.validate(real))
        assertEquals("15:00", real.day(Day.MON).single().start)
        assertEquals(real, Schedule.fromJson(real.toJson()))
    }

    @Test
    fun `max entries per day`() {
        assertEquals(
            listOf(Kind.TooManyEntries),
            violations(ScheduleEntry("normal", "06:00", "08:00"), ScheduleEntry("normal", "12:00", "13:00"), ScheduleEntry("normal", "18:00", "22:00")),
        )
    }

    @Test
    fun `modes resolution and time format`() {
        assertEquals(listOf(Kind.ModeNotAllowed), violations(ScheduleEntry("reduced", "06:00", "08:00")))
        assertEquals(listOf(Kind.OffResolution), violations(ScheduleEntry("normal", "06:05", "08:00")))
        assertEquals(listOf(Kind.BadTime), violations(ScheduleEntry("normal", "6:00", "08:00")))
        assertEquals(listOf(Kind.BadTime), violations(ScheduleEntry("normal", "06:00", "24:10")))
        assertEquals(listOf(Kind.StartNotBeforeEnd), violations(ScheduleEntry("normal", "08:00", "08:00")))
    }

    @Test
    fun `end of day is 24_00`() {
        assertEquals(emptyList<Kind>(), violations(ScheduleEntry("normal", "22:00", "24:00")))
        assertEquals(listOf(Kind.BadTime), violations(ScheduleEntry("normal", "24:00", "24:00")).take(1))
    }

    @Test
    fun `overlaps are rejected but touching periods are fine`() {
        assertEquals(listOf(Kind.Overlap), violations(ScheduleEntry("normal", "06:00", "09:00"), ScheduleEntry("comfort", "08:00", "10:00")))
        assertEquals(emptyList<Kind>(), violations(ScheduleEntry("normal", "06:00", "09:00"), ScheduleEntry("comfort", "09:00", "10:00")))
        val relaxed = rules.copy(overlapAllowed = true)
        assertEquals(emptyList<ScheduleViolation>(), relaxed.validateDay(Day.MON, listOf(ScheduleEntry("normal", "06:00", "09:00"), ScheduleEntry("comfort", "08:00", "10:00"))))
    }

    @Test
    fun `copy day and copy to all`() {
        val mon = listOf(ScheduleEntry("comfort", "05:30", "07:00"), ScheduleEntry("normal", "17:00", "22:00"))
        val edited = real.withDay(Day.MON, mon)
        assertEquals(edited.day(Day.MON), edited.copyDay(Day.MON, Day.SAT).day(Day.SAT))
        val all = edited.copyToAll(Day.MON)
        Day.entries.forEach { assertEquals(it.key, edited.day(Day.MON), all.day(it)) }
    }

    @Test
    fun `entries are sorted and positions renumbered`() {
        val s = Schedule.EMPTY.withDay(Day.TUE, listOf(ScheduleEntry("normal", "17:00", "22:00", 5), ScheduleEntry("comfort", "05:30", "07:00", 9)))
        assertEquals(listOf("05:30" to 0, "17:00" to 1), s.day(Day.TUE).map { it.start to it.position })
        assertTrue(s.toJson().toString().contains(""""tue":[{"mode":"comfort","start":"05:30","end":"07:00","position":0}"""))
    }
}
