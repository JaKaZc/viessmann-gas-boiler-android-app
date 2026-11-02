package com.vigasboiler.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartRangeTest {
    private fun assertRange(lo: Double, hi: Double, actual: Pair<Double, Double>) {
        assertEquals(lo, actual.first, 1e-9)
        assertEquals(hi, actual.second, 1e-9)
    }

    @Test
    fun `the y axis hugs the data with a margin on nice steps`() {
        assertRange(1.3, 1.6, niceRange(1.4, 1.5)) // pressure: no longer a flat line at the top of 0–1.5
        assertRange(25.0, 55.0, niceRange(28.0, 50.2)) // supply: 5 °C steps
        assertRange(18.5, 21.0, niceRange(19.1, 20.7)) // room: 0.5° steps
        assertRange(-5.0, 25.0, niceRange(0.5, 21.0)) // room and outside together: 5° steps
    }
}
