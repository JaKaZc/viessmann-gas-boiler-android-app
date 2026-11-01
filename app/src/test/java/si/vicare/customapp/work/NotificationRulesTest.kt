package si.vicare.customapp.work

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import si.vicare.customapp.feature.DeviceMessages
import si.vicare.customapp.feature.Feature
import si.vicare.customapp.feature.FeatureSnapshot
import si.vicare.customapp.feature.Fixtures
import si.vicare.customapp.feature.MessageLevel
import si.vicare.customapp.feature.MessageSource
import si.vicare.customapp.feature.PropertyValue
import si.vicare.customapp.feature.isDhw
import si.vicare.customapp.ratelimit.RateBudget
import si.vicare.customapp.settings.AppSettings

class NotificationRulesTest {

    private val base = FeatureSnapshot(Fixtures.boilerFeatures, Fixtures.gatewayFeatures, 0)
    private val settings = AppSettings()
    private val now = Instant.parse("2026-11-01T18:00:00Z").toEpochMilli()

    private fun withFeature(s: FeatureSnapshot, name: String, transform: (Feature) -> Feature) =
        s.copy(device = s.device.map { if (it.name == name) transform(it) else it })

    private fun withPressure(s: FeatureSnapshot, bar: Double) = withFeature(s, "heating.sensors.pressure.supply") {
        it.copy(properties = it.properties + ("value" to PropertyValue.Number(bar, "bar")))
    }

    private fun withStatus(s: FeatureSnapshot, vararg entries: Pair<String, String>) = withFeature(s, "device.messages.status.raw") {
        it.copy(properties = mapOf("entries" to PropertyValue.ArrayValue(entries.map { (code, ts) ->
            JsonObject(mapOf("errorCode" to JsonPrimitive(code), "timestamp" to JsonPrimitive(ts), "priority" to JsonPrimitive("status")))
        })))
    }

    private fun withGateway(s: FeatureSnapshot, status: String) = s.copy(gateway = s.gateway.map { f ->
        if (f.name != "gateway.devices") return@map f
        val list = (f.properties["devices"] as PropertyValue.ObjectValue).value as JsonArray
        val updated = JsonArray(list.map { d -> JsonObject((d as JsonObject) + ("status" to JsonPrimitive(status))) })
        f.copy(properties = mapOf("devices" to PropertyValue.ObjectValue("DeviceList", updated)))
    })

    @Test
    fun `real status messages are informational and sorted newest first`() {
        val messages = DeviceMessages.all(base)
        assertEquals(listOf("S.29", "S.154", "S.1"), messages.map { it.code })
        assertTrue(messages.all { it.source == MessageSource.Status && it.level == MessageLevel.Info })
    }

    @Test
    fun `levels follow the code prefix`() {
        val levels = DeviceMessages.all(withStatus(base, "F.74" to "2026-11-01T17:00:00.000Z", "A.12" to "2026-11-01T17:00:00.000Z",
            "P.4" to "2026-11-01T17:00:00.000Z", "I.56" to "2026-11-01T17:00:00.000Z")).associate { it.code to it.level }
        assertEquals(MessageLevel.Error, levels["F.74"])
        assertEquals(MessageLevel.Warning, levels["A.12"])
        assertEquals(MessageLevel.Info, levels["P.4"])
        assertEquals(MessageLevel.Info, levels["I.56"])
    }

    @Test
    fun `status codes never raise a banner`() {
        assertTrue(DeviceMessages.bannerMessages(base, now).isEmpty())
    }

    @Test
    fun `new status codes are silent, new faults notify once`() {
        assertTrue(NotificationRules.onSnapshot(null, base, settings, NotifierState(), now).first.isEmpty())
        val withNewStatus = withStatus(base, "S.29" to "2026-11-01T12:37:41.000Z", "S.125" to "2026-11-01T17:50:00.000Z")
        assertTrue(NotificationRules.onSnapshot(base, withNewStatus, settings, NotifierState(), now).first.none { it is Alert.NewMessage })

        val withFault = withStatus(base, "S.29" to "2026-11-01T12:37:41.000Z", "F.74" to "2026-11-01T17:55:00.000Z")
        val (alerts, _) = NotificationRules.onSnapshot(base, withFault, settings, NotifierState(), now)
        assertEquals(listOf("F.74"), alerts.filterIsInstance<Alert.NewMessage>().map { it.message.code })
        assertEquals(listOf("F.74"), DeviceMessages.bannerMessages(withFault, now).map { it.code })
        assertTrue(NotificationRules.onSnapshot(withFault, withFault, settings, NotifierState(), now).first.isEmpty())
    }

    @Test
    fun `pressure alert fires when leaving the range and re-arms after returning`() {
        var state = NotifierState()
        val low = withPressure(base, 0.8)
        NotificationRules.onSnapshot(base, low, settings, state, now).let { (alerts, s) ->
            assertEquals(1, alerts.filterIsInstance<Alert.Pressure>().size)
            state = s
        }
        assertTrue(NotificationRules.onSnapshot(low, low, settings, state, now).first.none { it is Alert.Pressure })
        state = NotificationRules.onSnapshot(low, base, settings, state, now).second
        assertEquals(1, NotificationRules.onSnapshot(base, low, settings, state, now).first.filterIsInstance<Alert.Pressure>().size)
    }

    @Test
    fun `gateway offline alerts after the configured time, once`() {
        val offline = withGateway(base, "offline")
        var (alerts, state) = NotificationRules.onSnapshot(base, offline, settings, NotifierState(), now)
        assertTrue(alerts.none { it is Alert.GatewayOffline })
        val later = now + settings.offlineAlertMinutes * 60_000L
        NotificationRules.onSnapshot(offline, offline, settings, state, later).let { (a, s) ->
            assertEquals(Alert.GatewayOffline(now), a.single { it is Alert.GatewayOffline })
            state = s
        }
        assertTrue(NotificationRules.onSnapshot(offline, offline, settings, state, later + 3_600_000).first.none { it is Alert.GatewayOffline })
        // Back online resets everything.
        val online = NotificationRules.onSnapshot(offline, base, settings, state, later).second
        assertEquals(NotifierState(), online)
        assertEquals(true, NotificationRules.gatewayOnline(base))
    }

    @Test
    fun `failed background refreshes count as offline too`() {
        var state = NotifierState()
        state = NotificationRules.onUnreachable(settings, state, now).second
        val (alerts, _) = NotificationRules.onUnreachable(settings, state, now + 61 * 60_000L)
        assertEquals(1, alerts.size)
    }

    @Test
    fun `budget warning once per soft-limit episode`() {
        val high = RateBudget(used10m = 100, used24h = 100, block = null)
        val (first, state) = NotificationRules.onBudget(high, NotifierState())
        assertEquals(1, first.size)
        assertTrue(NotificationRules.onBudget(high, state).first.isEmpty())
        val reset = NotificationRules.onBudget(RateBudget(10, 10, null), state).second
        assertEquals(1, NotificationRules.onBudget(high, reset).first.size)
    }

    @Test
    fun `dhw features are recognised`() {
        val dhw = Fixtures.boilerFeatures.filter { it.isDhw }.map { it.name }
        assertTrue("heating.dhw.temperature.main" in dhw)
        assertTrue("heating.configuration.dhwCylinderSize" in dhw)
        assertTrue("heating.gas.consumption.summary.dhw" in dhw)
        assertTrue("heating.gas.consumption.summary.heating" !in dhw)
    }
}
