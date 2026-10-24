package com.binarysimple.hvacwidget.seat

import com.binarysimple.hvacwidget.car.CarFunctionPort
import com.binarysimple.hvacwidget.functions.FunctionKind
import com.binarysimple.hvacwidget.functions.WidgetFunctions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seat memory (S010 Task 3): restore-only, slot ladders are model-dependent registry data. */
class SeatMemoryTest {

    private class RecordingPort : CarFunctionPort {
        override val available: Boolean = true
        val writes = mutableListOf<Triple<Int, Int, Int>>()
        override fun getInt(propertyId: Int, areaId: Int): Int? = null
        override fun setInt(propertyId: Int, areaId: Int, value: Int): Boolean {
            writes.add(Triple(propertyId, areaId, value))
            return true
        }
    }

    @Test
    fun `cityray ladder has two slots - atlas three - preface unknown`() {
        assertEquals(listOf(760219140, 760219139), WidgetFunctions.Model.CITYRAY.memorySlots)
        assertEquals(listOf(760219140, 760219139, 760219138), WidgetFunctions.Model.ATLAS.memorySlots)
        assertTrue(WidgetFunctions.Model.PREFACE.memorySlots.isEmpty())
    }

    @Test
    fun `registry builds one SLOT spec per model slot`() {
        val cityray = WidgetFunctions.all(WidgetFunctions.Model.CITYRAY).filter { it.kind == FunctionKind.SLOT }
        assertEquals(listOf(760219140, 760219139), cityray.map { it.slots!![0] })
        assertEquals(listOf("memory_1", "memory_2"), cityray.map { it.id })

        val atlas = WidgetFunctions.all(WidgetFunctions.Model.ATLAS).filter { it.kind == FunctionKind.SLOT }
        assertEquals(listOf(760219140, 760219139, 760219138), atlas.map { it.slots!![0] })

        val preface = WidgetFunctions.all(WidgetFunctions.Model.PREFACE).filter { it.kind == FunctionKind.SLOT }
        assertTrue(preface.isEmpty())
    }

    @Test
    fun `restore writes the slot value to the memory property and driver area`() {
        val port = RecordingPort()
        val spec = WidgetFunctions.byId(WidgetFunctions.Model.CITYRAY, "memory_2")!!
        assertTrue(SeatMemory.restore(port, spec))
        assertEquals(listOf(Triple(760219136, 1, 760219139)), port.writes)
    }

    @Test
    fun `restore refuses non-slot specs`() {
        val port = RecordingPort()
        val wheel = WidgetFunctions.byId(WidgetFunctions.Model.CITYRAY, "wheel")!!
        assertFalse(SeatMemory.restore(port, wheel))
        assertNull(SeatMemory.restoreValue(wheel))
        assertTrue(port.writes.isEmpty())
    }

    @Test
    fun `active slot mapping never guesses a foreign value`() {
        val cityray = WidgetFunctions.Model.CITYRAY
        assertEquals(0, SeatMemory.activeSlotIndex(cityray, 760219140))
        assertEquals(1, SeatMemory.activeSlotIndex(cityray, 760219139))
        // 760219138 is the ATLAS third slot — unknown to Cityray: "no active slot"
        assertEquals(-1, SeatMemory.activeSlotIndex(cityray, 760219138))
        assertEquals(-1, SeatMemory.activeSlotIndex(cityray, null))

        assertEquals(2, SeatMemory.activeSlotIndex(WidgetFunctions.Model.ATLAS, 760219138))
    }
}
