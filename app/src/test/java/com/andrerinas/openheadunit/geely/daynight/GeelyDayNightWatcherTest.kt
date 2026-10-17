package com.andrerinas.openheadunit.geely.daynight

import com.andrerinas.openheadunit.geely.hvac.CarFunctionPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeelyDayNightWatcherTest {

    /** Records the reads; the quiet path is the one the watcher must use. */
    private class FakePort(var value: Int?) : CarFunctionPort {
        override val available = true
        var quietReads = 0
        var loudReads = 0
        override fun getInt(propertyId: Int, areaId: Int): Int? {
            loudReads++
            return value
        }

        override fun getIntQuiet(propertyId: Int, areaId: Int): Int? {
            quietReads++
            assertEquals(DayNight.PROPERTY, propertyId)
            assertEquals(DayNight.AREA, areaId)
            return value
        }

        override fun setInt(propertyId: Int, areaId: Int, value: Int): Boolean {
            throw AssertionError("the watch must never write to the car")
        }
    }

    @Test
    fun `first reading applies immediately as night`() {
        val port = FakePort(DayNight.NIGHT)
        val flips = mutableListOf<Boolean>()
        val w = GeelyDayNightWatcher(port) { flips += it }
        w.pollOnce()
        assertEquals(listOf(true), flips)
        assertEquals(1, port.quietReads)
        assertEquals(0, port.loudReads)
    }

    @Test
    fun `day reading maps to isNight false`() {
        val port = FakePort(DayNight.DAY)
        val flips = mutableListOf<Boolean>()
        GeelyDayNightWatcher(port) { flips += it }.pollOnce()
        assertEquals(listOf(false), flips)
    }

    @Test
    fun `a flip needs two polls with the new value`() {
        val port = FakePort(DayNight.DAY)
        val flips = mutableListOf<Boolean>()
        val w = GeelyDayNightWatcher(port) { flips += it }
        w.pollOnce()
        port.value = DayNight.NIGHT
        w.pollOnce()
        assertEquals(listOf(false), flips)
        w.pollOnce()
        assertEquals(listOf(false, true), flips)
    }

    @Test
    fun `a null read does not flip and resets the pending candidate`() {
        val port = FakePort(DayNight.DAY)
        val flips = mutableListOf<Boolean>()
        val w = GeelyDayNightWatcher(port) { flips += it }
        w.pollOnce()
        port.value = DayNight.NIGHT
        w.pollOnce()
        port.value = null
        w.pollOnce()
        // The night candidate was dropped with the failed sample: two fresh reads are needed.
        port.value = DayNight.NIGHT
        w.pollOnce()
        assertEquals(listOf(false), flips)
        w.pollOnce()
        assertEquals(listOf(false, true), flips)
    }

    @Test
    fun `garbage value never flips`() {
        val port = FakePort(42)
        val flips = mutableListOf<Boolean>()
        GeelyDayNightWatcher(port) { flips += it }.pollOnce()
        assertTrue(flips.isEmpty())
    }
}
