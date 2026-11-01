package si.vicare.customapp.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.vicare.customapp.discovery.Samples.GATEWAY_SERIAL
import si.vicare.customapp.discovery.Samples.INSTALLATION_ID

class DiscoveryParserTest {

    @Test
    fun `installations with numeric id and nested gateways`() {
        assertEquals(mapOf(INSTALLATION_ID to listOf(GATEWAY_SERIAL)), DiscoveryParser.parseInstallations(Samples.installations))
    }

    @Test
    fun `gateways list`() {
        assertEquals(listOf(GatewayRef(INSTALLATION_ID, GATEWAY_SERIAL)), DiscoveryParser.parseGateways(Samples.gateways))
    }

    @Test
    fun `device ids keep order`() {
        assertEquals(listOf("gateway", "0"), DiscoveryParser.parseDeviceIds(Samples.devices))
    }

    @Test
    fun `merge removes duplicates and keeps gateways only known to one endpoint`() {
        val merged = DiscoveryParser.mergeGateways(
            mapOf("1" to listOf("A")),
            listOf(GatewayRef("1", "A"), GatewayRef("2", "B")),
        )
        assertEquals(listOf(GatewayRef("1", "A"), GatewayRef("2", "B")), merged)
    }

    @Test
    fun `malformed or error bodies yield empty results instead of crashing`() {
        assertTrue(DiscoveryParser.parseInstallations("not json").isEmpty())
        assertTrue(DiscoveryParser.parseDeviceIds("""{"statusCode":403,"errorType":"FORBIDDEN"}""").isEmpty())
    }
}
