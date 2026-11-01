package si.vicare.customapp.feature

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.vicare.customapp.feature.ValidationError.Kind

class CommandValidatorTest {

    private val setCurve = Fixtures.command("heating.circuits.0.heating.curve", "setCurve")
    private val setMode = Fixtures.command("heating.circuits.0.operating.modes.active", "setMode")
    private val setNormal = Fixtures.command("heating.circuits.0.operating.programs.normal", "setTemperature")
    private val holiday = Fixtures.command("heating.operating.programs.holiday", "schedule")
    private val setSchedule = Fixtures.command("heating.circuits.0.heating.schedule", "setSchedule")

    private fun kinds(command: Command, vararg values: Pair<String, JsonElement>) =
        CommandValidator.validate(command, values.toMap()).map { it.param to it.kind }

    private fun n(v: Number) = JsonPrimitive(v)
    private fun s(v: String) = JsonPrimitive(v)

    @Test
    fun `heating curve min max and stepping`() {
        assertEquals(emptyList<Any>(), kinds(setCurve, "slope" to n(1.4), "shift" to n(0)))
        assertEquals(emptyList<Any>(), kinds(setCurve, "slope" to n(0.2), "shift" to n(-13)))
        assertEquals(emptyList<Any>(), kinds(setCurve, "slope" to n(3.5), "shift" to n(40)))
        assertEquals(listOf("slope" to Kind.OffStep), kinds(setCurve, "slope" to n(1.45), "shift" to n(0)))
        assertEquals(listOf("slope" to Kind.BelowMin), kinds(setCurve, "slope" to n(0.1), "shift" to n(0)))
        assertEquals(listOf("shift" to Kind.AboveMax), kinds(setCurve, "slope" to n(1.0), "shift" to n(41)))
        assertEquals(listOf("shift" to Kind.Missing), kinds(setCurve, "slope" to n(1.0)))
    }

    @Test
    fun `floating point steps like 0_3 are accepted`() {
        // 0.2 + 0.1 is not exactly 0.3 in binary floating point.
        assertEquals(emptyList<Any>(), kinds(setCurve, "slope" to n(0.3), "shift" to n(0)))
        assertEquals(emptyList<Any>(), kinds(setCurve, "slope" to n(2.7), "shift" to n(0)))
    }

    @Test
    fun `enum type and unknown params`() {
        assertEquals(emptyList<Any>(), kinds(setMode, "mode" to s("standby")))
        assertEquals(listOf("mode" to Kind.NotInEnum), kinds(setMode, "mode" to s("dhwAndHeating")))
        assertEquals(listOf("mode" to Kind.WrongType), kinds(setMode, "mode" to n(1)))
        assertEquals(listOf("extra" to Kind.UnknownParam), kinds(setMode, "mode" to s("heating"), "extra" to s("x")))
    }

    @Test
    fun `program temperature uses targetTemperature`() {
        assertEquals(emptyList<Any>(), kinds(setNormal, "targetTemperature" to n(21)))
        assertEquals(listOf("targetTemperature" to Kind.AboveMax), kinds(setNormal, "targetTemperature" to n(38)))
        assertEquals(listOf("targetTemperature" to Kind.OffStep), kinds(setNormal, "targetTemperature" to n(20.5)))
        assertEquals(listOf("targetTemperature" to Kind.WrongType), kinds(setNormal, "targetTemperature" to s("21")))
    }

    @Test
    fun `holiday dates use the regex and must be ordered`() {
        assertEquals(emptyList<Any>(), kinds(holiday, "start" to s("2026-12-24"), "end" to s("2027-01-02")))
        assertEquals(emptyList<Any>(), kinds(holiday, "start" to s("2026-12-24"), "end" to s("2026-12-24")))
        assertEquals(listOf("start" to Kind.PatternMismatch), kinds(holiday, "start" to s("24.12.2026"), "end" to s("2027-01-02")))
        assertEquals(listOf("end" to Kind.EndBeforeStart), kinds(holiday, "start" to s("2026-12-24"), "end" to s("2026-12-20")))
    }

    @Test
    fun `schedule param is checked with the schedule rules`() {
        val current = Schedule.fromJson((Fixtures.boiler("heating.circuits.0.heating.schedule").properties["entries"] as PropertyValue.ObjectValue).value)!!
        assertEquals(emptyList<Any>(), kinds(setSchedule, "newSchedule" to current.toJson()))

        val bad = current.withDay(Day.MON, listOf(ScheduleEntry("reduced", "06:05", "08:00")))
        val errors = CommandValidator.validate(setSchedule, mapOf("newSchedule" to bad.toJson()))
        assertTrue(errors.all { it.kind == Kind.InvalidSchedule })
        assertEquals(2, errors.size) // mode not allowed + 06:05 off the 10 minute resolution
    }

    @Test
    fun `body contains only declared params`() {
        val body = CommandValidator.body(setCurve, mapOf("slope" to n(1.4), "shift" to n(2)))
        assertEquals("""{"slope":1.4,"shift":2}""", body.toString())
    }
}
