package com.vigasboiler.app.mock

import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import com.vigasboiler.app.feature.FeatureParser

class MockFeatureStoreTest {

    private val store = MockFeatureStore(File("../docs/feature-dump/07_device_features_i0_g0_d0.json").readText())
    private fun feature(name: String) = FeatureParser.parse(store.body(name)).single()

    @Test
    fun `filter returns only the named features`() {
        assertEquals(110, FeatureParser.parse(store.body(null)).size)
        assertEquals(1, FeatureParser.parse(store.body("heating.circuits.0.heating.curve")).size)
    }

    @Test
    fun `commands change the matching properties`() {
        assertEquals(200, store.apply("heating.circuits.0.operating.modes.active", "setMode", buildJsonObject { put("mode", "standby") }).first)
        assertEquals("standby", feature("heating.circuits.0.operating.modes.active").text())

        store.apply("heating.circuits.0.operating.programs.normal", "setTemperature", buildJsonObject { put("targetTemperature", 22) })
        assertEquals(22.0, feature("heating.circuits.0.operating.programs.normal").number("temperature")!!, 0.0)

        store.apply("heating.circuits.0.heating.curve", "setCurve", buildJsonObject { put("slope", 1.2); put("shift", 3) })
        val curve = feature("heating.circuits.0.heating.curve")
        assertEquals(1.2, curve.number("slope")!!, 0.0)
        assertEquals(3.0, curve.number("shift")!!, 0.0)

        store.apply("heating.operating.programs.holiday", "schedule", buildJsonObject { put("start", "2026-12-24"); put("end", "2027-01-02") })
        val holiday = feature("heating.operating.programs.holiday")
        assertEquals(true, holiday.bool("active"))
        assertEquals("2026-12-24", holiday.text("start"))
    }

    @Test
    fun `command path is split at the last features segment`() {
        assertEquals(
            "heating.circuits.0.heating.curve" to "setCurve",
            MockApiInterceptor.commandTarget("/iot/v2/features/installations/ID_2/gateways/SERIAL_1/devices/0/features/heating.circuits.0.heating.curve/commands/setCurve"),
        )
    }

    @Test
    fun `non executable commands answer 403 like the Basic plan`() {
        assertEquals(403, store.apply("heating.circuits.0.temperature.levels", "setMax", buildJsonObject { put("temperature", JsonPrimitive(70)) }).first)
    }
}
