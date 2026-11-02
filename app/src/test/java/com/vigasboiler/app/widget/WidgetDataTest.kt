package com.vigasboiler.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.vigasboiler.app.feature.FeatureParser
import com.vigasboiler.app.feature.FeatureSnapshot
import com.vigasboiler.app.feature.Fixtures

class WidgetDataTest {

    private val minute = 60_000L
    private val fetched = 1_000_000_000L
    private val snapshot = FeatureSnapshot(
        Fixtures.boilerFeatures,
        FeatureParser.parse(Fixtures.read("06_gateway_features_i0_g0.json")),
        fetched,
    )

    @Test
    fun `values come from the snapshot`() {
        val d = WidgetData.from(snapshot, fetched + 30 * minute, 30, fetched + minute)
        assertEquals(20.7, d.room!!, 0.0)
        assertEquals(21.0, d.target!!, 0.0)
        assertEquals(9.8, d.outside!!, 0.0)
        assertEquals("heating", d.mode)
        assertEquals("normal", d.program)
        assertTrue(d.burnerOn)
        assertEquals(15.3, d.modulation!!, 0.0)
        assertEquals(41.9, d.supply!!, 0.0)
        assertEquals(1.5, d.pressure!!, 0.0)
        assertEquals(true, d.online)
        assertEquals(-37.0, d.wifiDbm!!, 0.0)
        assertNull(d.fault) // the sample only has status (S.) messages
        assertEquals(fetched, d.updatedAtMillis)
        assertFalse(d.stale)
    }

    @Test
    fun `next refresh is WorkManager's time when it lies ahead`() {
        assertEquals(fetched + 31 * minute, WidgetData.nextRefresh(fetched + 31 * minute, fetched, 30 * minute, fetched + minute))
    }

    @Test
    fun `without a future schedule the next refresh is one interval after the last`() {
        assertEquals(fetched + 30 * minute, WidgetData.nextRefresh(null, fetched, 30 * minute, fetched + minute))
        // Running right now: WorkManager's time is in the past.
        assertEquals(fetched + 30 * minute, WidgetData.nextRefresh(fetched, fetched, 30 * minute, fetched + minute))
        // Several intervals missed: the next slot after now, never a time in the past.
        assertEquals(fetched + 90 * minute, WidgetData.nextRefresh(null, fetched, 30 * minute, fetched + 75 * minute))
    }

    @Test
    fun `data older than two intervals is stale`() {
        assertFalse(WidgetData.from(snapshot, null, 30, fetched + 60 * minute).stale)
        assertTrue(WidgetData.from(snapshot, null, 30, fetched + 66 * minute).stale)
    }
}
