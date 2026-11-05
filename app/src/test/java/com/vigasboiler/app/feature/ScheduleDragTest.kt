package com.vigasboiler.app.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.vigasboiler.app.feature.DialPart.Body
import com.vigasboiler.app.feature.DialPart.End
import com.vigasboiler.app.feature.DialPart.Start

class ScheduleDragTest {

    private fun e(start: String, end: String, mode: String = "normal") = ScheduleEntry(mode, start, end)
    private val two = listOf(e("06:00", "09:00"), e("17:00", "22:00", "comfort"))
    private fun times(list: List<ScheduleEntry>) = list.map { "${it.start}-${it.end}" }

    @Test
    fun `each handle of every period moves on its own, in resolution steps`() {
        assertEquals(listOf("05:30-09:00", "17:00-22:00"), times(ScheduleDrag.apply(two, DialGrab(0, Start), -32, 10)))
        assertEquals(listOf("06:00-09:50", "17:00-22:00"), times(ScheduleDrag.apply(two, DialGrab(0, End), 47, 10)))
        assertEquals(listOf("06:00-09:00", "16:30-22:00"), times(ScheduleDrag.apply(two, DialGrab(1, Start), -30, 10)))
        assertEquals(listOf("06:00-09:00", "17:00-23:00"), times(ScheduleDrag.apply(two, DialGrab(1, End), 60, 10)))
    }

    @Test
    fun `dragging the arc moves the whole period and keeps its length`() {
        assertEquals(listOf("07:00-10:00", "17:00-22:00"), times(ScheduleDrag.apply(two, DialGrab(0, Body), 60, 10)))
    }

    @Test
    fun `a period stops at its neighbour, at midnight and at its minimum length`() {
        assertEquals(listOf("14:00-17:00", "17:00-22:00"), times(ScheduleDrag.apply(two, DialGrab(0, Body), 1200, 10)))
        assertEquals(listOf("06:00-17:00", "17:00-22:00"), times(ScheduleDrag.apply(two, DialGrab(0, End), 2000, 10)))
        assertEquals(listOf("06:00-09:00", "09:00-22:00"), times(ScheduleDrag.apply(two, DialGrab(1, Start), -2000, 10)))
        assertEquals(listOf("00:00-09:00", "17:00-22:00"), times(ScheduleDrag.apply(two, DialGrab(0, Start), -2000, 10)))
        assertEquals(listOf("06:00-09:00", "17:00-24:00"), times(ScheduleDrag.apply(two, DialGrab(1, End), 2000, 10)))
        assertEquals(listOf("08:50-09:00", "17:00-22:00"), times(ScheduleDrag.apply(two, DialGrab(0, Start), 500, 10)))
    }

    @Test
    fun `periods that touch share their boundary`() {
        val touching = listOf(e("06:00", "10:00"), e("10:00", "12:00", "comfort"))
        assertEquals(listOf("06:00-11:00", "11:00-12:00"), times(ScheduleDrag.apply(touching, DialGrab(0, End), 60, 10)))
        assertEquals(listOf("06:00-09:00", "09:00-12:00"), times(ScheduleDrag.apply(touching, DialGrab(1, Start), -60, 10)))
        assertEquals(listOf("06:00-11:50", "11:50-12:00"), times(ScheduleDrag.apply(touching, DialGrab(0, End), 500, 10)))
    }

    @Test
    fun `no movement changes nothing, even off the resolution`() {
        val off = listOf(e("06:03", "09:07"))
        assertEquals(off, ScheduleDrag.apply(off, DialGrab(0, End), 0, 10))
    }

    @Test
    fun `a touch finds the nearest handle, else the period`() {
        assertEquals(listOf(DialGrab(0, Start)), ScheduleDrag.hit(two, 370.0, 30.0))
        assertEquals(listOf(DialGrab(1, End)), ScheduleDrag.hit(two, 1310.0, 30.0))
        assertEquals(listOf(DialGrab(0, Body)), ScheduleDrag.hit(two, 450.0, 30.0))
        assertTrue(ScheduleDrag.hit(two, 700.0, 30.0).isEmpty())
    }

    @Test
    fun `handles on top of each other at midnight are told apart by the direction`() {
        val night = listOf(e("00:00", "05:00"), e("22:00", "24:00"))
        val both = ScheduleDrag.hit(night, 5.0, 30.0)
        assertEquals(setOf(DialGrab(0, Start), DialGrab(1, End)), both.toSet())
        assertEquals(DialGrab(0, Start), ScheduleDrag.pick(night, both, 30, 10))
        assertEquals(DialGrab(1, End), ScheduleDrag.pick(night, both, -30, 10))
    }

    @Test
    fun `a new period goes into the middle of the largest gap`() {
        assertEquals(6 * 60 to 9 * 60, ScheduleDrag.freeSlot(listOf(e("15:00", "23:00")), 10))
        assertEquals(10 * 60 + 30 to 12 * 60 + 30, ScheduleDrag.freeSlot(listOf(e("00:00", "10:30"), e("12:30", "24:00")), 10))
        assertEquals(null, ScheduleDrag.freeSlot(listOf(e("00:00", "24:00")), 10))
    }

    @Test
    fun `the way round the ring takes the short side over midnight`() {
        assertEquals(20.0, ScheduleDrag.ringDelta(1430.0, 10.0), 1e-9)
        assertEquals(-20.0, ScheduleDrag.ringDelta(10.0, 1430.0), 1e-9)
    }
}
