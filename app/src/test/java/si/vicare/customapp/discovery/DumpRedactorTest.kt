package si.vicare.customapp.discovery

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import si.vicare.customapp.discovery.Samples.BOILER_SERIAL
import si.vicare.customapp.discovery.Samples.CRM_ID
import si.vicare.customapp.discovery.Samples.EQUIPMENT_NUMBER
import si.vicare.customapp.discovery.Samples.GATEWAY_SERIAL
import si.vicare.customapp.discovery.Samples.INSTALLATION_ID

class DumpRedactorTest {

    private val files = listOf(Samples.me, Samples.installations, Samples.gateways, Samples.devices, Samples.deviceFeatures)

    private fun redactAll(): List<String> {
        // Index 0 is the users/me response, masked as a personal document like the exporter does.
        val redactor = DumpRedactor()
        files.forEachIndexed { i, f -> redactor.collect(f, personalDocument = i == 0) }
        return files.mapIndexed { i, f -> redactor.redact(f, personalDocument = i == 0) }
    }

    @Test
    fun `identifiers and personal data are gone from every file`() {
        val all = redactAll().joinToString("\n")
        listOf(
            INSTALLATION_ID, GATEWAY_SERIAL, BOILER_SERIAL, EQUIPMENT_NUMBER, CRM_ID,
            "a1b2c3d4-e5f6-7890-abcd-ef0123456789", "jane@example.com", "Jane", "Doe", "Main street", "Kranj", "46.05",
            "051123456", "4000", "FEMALE",
        ).forEach { assertFalse("leaked: $it", all.contains(it)) }
    }

    @Test
    fun `placeholders are consistent across files and inside command uris`() {
        val out = redactAll()
        val features = out.last()
        assertTrue(features.contains("/installations/ID_"))
        assertTrue(features.contains("/gateways/SERIAL_"))
        // The same gateway serial maps to the same placeholder in the gateways file.
        val placeholder = Regex("/gateways/(SERIAL_\\d+)/").find(features)!!.groupValues[1]
        assertTrue(out[2].contains(placeholder))
    }

    @Test
    fun `feature structure and non-sensitive values survive`() {
        val features = redactAll().last()
        Json.parseToJsonElement(features) // still valid JSON
        assertTrue(features.contains("heating.circuits.0.operating.modes.active"))
        assertTrue(features.contains("\"heating\""))
        assertTrue(features.contains("Radiators")) // property objects named "name" are kept
        assertTrue(features.contains("\"name\": \"setMode\"")) // command names are not personal
        assertTrue(features.contains("7.4")) // ordinary numeric values are untouched
        assertTrue(redactAll()[3].contains("\"gateway\"")) // short device IDs stay readable
    }

    @Test
    fun `personal document keeps only structure and locale fields`() {
        val me = Json.parseToJsonElement(redactAll().first()).toString()
        assertTrue(me.contains("\"countryCode\":\"si\""))
        assertTrue(me.contains("\"locale\":\"en-si\""))
        assertTrue(me.contains("\"userState\":\"ACTIVE\""))
        assertTrue(me.contains("\"addressline1\":\"REDACTED\""))
    }

    @Test
    fun `coordinates in feature properties are masked without corrupting property types`() {
        val features = redactAll().last()
        assertFalse(features.contains("46.2391"))
        assertFalse(features.contains("14.3556"))
        // Regression: "number" from the latitude object must not be scrubbed from other features.
        val outside = Regex("\"heating.sensors.temperature.outside\"[\\s\\S]*?\"type\": \"(\\w+)\"").find(features)!!
        assertTrue(outside.groupValues[1], outside.groupValues[1] == "number")
        assertTrue(features.contains("\"unit\": \"degree\""))
    }

    @Test
    fun `personal keys are recognised by fragment`() {
        listOf("addressline1", "telephone", "buildingEmail", "phoneNumber", "faxNumber", "postalCode", "macAddress")
            .forEach { assertTrue(it, DumpRedactor.isPersonalKey(it)) }
        listOf("name", "value", "feature", "modelId", "countryCode")
            .forEach { assertFalse(it, DumpRedactor.isPersonalKey(it)) }
    }

    @Test
    fun `non json bodies are still scrubbed of known identifiers`() {
        val redactor = DumpRedactor()
        redactor.collect(Samples.gateways)
        assertFalse(redactor.redact("error for gateway $GATEWAY_SERIAL").contains(GATEWAY_SERIAL))
    }
}
