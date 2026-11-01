package si.vicare.customapp.feature

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureParserTest {

    @Test
    fun `real boiler dump parses completely`() {
        val features = Fixtures.boilerFeatures
        assertEquals(110, features.size)
        assertEquals(75, features.count { it.isEnabled })
        assertEquals(32, features.sumOf { it.commands.size })
        assertEquals(2, Fixtures.gatewayFeatures.size)
    }

    @Test
    fun `numbers keep value and unit`() {
        val modulation = Fixtures.boiler("heating.burners.0.modulation")
        assertEquals(15.3, modulation.number()!!, 1e-9)
        assertEquals("percent", modulation.unit())
        val stats = Fixtures.boiler("heating.burners.0.statistics")
        assertEquals(8008.0, stats.number("hours")!!, 0.0)
        assertNull("empty unit becomes null", stats.unit("starts"))
        assertEquals(1.5, Fixtures.boiler("heating.sensors.pressure.supply").number()!!, 0.0)
    }

    @Test
    fun `strings booleans arrays and structured types`() {
        assertEquals("heating", Fixtures.boiler("heating.circuits.0.operating.modes.active").text())
        assertEquals(true, Fixtures.boiler("heating.burners.0").bool("active"))
        val status = Fixtures.boiler("device.messages.status.raw").properties["entries"] as PropertyValue.ArrayValue
        assertEquals(3, status.items.size)
        val schedule = Fixtures.boiler("heating.circuits.0.heating.schedule").properties["entries"]
        assertTrue(schedule is PropertyValue.ObjectValue && schedule.type == "Schedule")
        val vin = Fixtures.boiler("device.serial").properties["vin"]
        assertTrue(vin is PropertyValue.ObjectValue && vin.type == "VinEntry")
    }

    @Test
    fun `commands and constraints`() {
        val setCurve = Fixtures.command("heating.circuits.0.heating.curve", "setCurve")
        assertTrue(setCurve.isExecutable)
        assertTrue(setCurve.uri.endsWith("/heating.circuits.0.heating.curve/commands/setCurve"))
        val slope = setCurve.params.getValue("slope")
        assertEquals("number", slope.type)
        assertEquals(0.2, slope.constraints.min!!, 0.0)
        assertEquals(3.5, slope.constraints.max!!, 0.0)
        assertEquals(0.1, slope.constraints.stepping!!, 0.0)

        val setSchedule = Fixtures.command("heating.circuits.0.heating.schedule", "setSchedule")
        val c = setSchedule.params.getValue("newSchedule").constraints
        assertEquals(listOf("normal", "comfort"), c.modes)
        assertEquals(2, c.maxEntries)
        assertEquals(10, c.resolution)
        assertEquals(false, c.overlapAllowed)
        assertEquals("reduced", c.defaultMode)

        assertFalse(Fixtures.command("heating.circuits.0.temperature.levels", "setMax").isExecutable)
    }

    @Test
    fun `groups follow the name prefix`() {
        assertEquals("heating.circuits", Fixtures.boiler("heating.circuits.0.heating.curve").group)
        assertEquals("heating.burners", Fixtures.boiler("heating.burners.0.modulation").group)
        assertEquals("device.messages", Fixtures.boiler("device.messages.status.raw").group)
    }

    @Test
    fun `type mismatches degrade to Unknown instead of failing`() {
        val body = """{"data":[
            {"feature":"x.number.as.string","isEnabled":true,"properties":{"value":{"type":"number","value":"12"}},"commands":{}},
            {"feature":"x.future","isEnabled":true,"properties":{"value":{"type":"Quantum","value":7}},"commands":{}},
            {"isEnabled":true,"properties":{}},
            "garbage"
        ]}"""
        val features = FeatureParser.parse(body)
        assertEquals(listOf("x.number.as.string", "x.future"), features.map { it.name })
        val mismatch = features[0].properties.getValue("value")
        assertTrue(mismatch is PropertyValue.Unknown && mismatch.raw == JsonPrimitive("12"))
        assertTrue(features[1].properties.getValue("value") is PropertyValue.Unknown)
    }

    @Test
    fun `non json and error bodies give no features`() {
        assertTrue(FeatureParser.parse("not json").isEmpty())
        assertTrue(FeatureParser.parse("""{"statusCode":403,"errorType":"FORBIDDEN"}""").isEmpty())
    }
}
