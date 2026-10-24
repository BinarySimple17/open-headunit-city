package com.binarysimple.hvacwidget.functions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure value semantics of the widget specs (S010 Task 2). */
class FunctionSpecTest {

    private val wheel = FunctionSpec(
        id = "wheel", propertyId = 269025536, areaId = Int.MIN_VALUE,
        kind = FunctionKind.LEVELS, levels = intArrayOf(0, 269025537, 269025538, 269025539),
    )
    private val seat = FunctionSpec(
        id = "seat_driver", propertyId = 268763648, areaId = 1,
        kind = FunctionKind.LEVELS,
        levels = intArrayOf(0, 268763649, 268763650, 268763651),
        autoReadCode = 0x1005020F,
    )
    private val recirc = FunctionSpec(
        id = "recirc", propertyId = 268632320, areaId = Int.MIN_VALUE,
        kind = FunctionKind.TOGGLE, onValue = 268632321, offValue = 268632322,
    )
    private val memory = FunctionSpec(
        id = "memory_1", propertyId = 760219136, areaId = 1,
        kind = FunctionKind.SLOT, slots = intArrayOf(760219140),
    )

    @Test
    fun `level cycles off - L1 - L2 - L3 - off`() {
        assertEquals(269025537, wheel.nextLevelValue(0))
        assertEquals(269025538, wheel.nextLevelValue(1))
        assertEquals(269025539, wheel.nextLevelValue(2))
        assertEquals(0, wheel.nextLevelValue(3))
    }

    @Test
    fun `unknown current ordinal starts the cycle at L1`() {
        assertEquals(269025537, wheel.nextLevelValue(-5))
        assertEquals(269025537, wheel.nextLevelValue(9))
    }

    @Test
    fun `levelOf folds encoded and plain-ordinal reads`() {
        assertEquals(2, wheel.levelOf(269025538))
        assertEquals(2, wheel.levelOf(2))
        assertEquals(1, wheel.levelOf(269025537))
        assertEquals(0, wheel.levelOf(0))
        assertEquals(0, wheel.levelOf(null))
        assertEquals(0, wheel.levelOf(999))
        assertEquals(0, wheel.levelOf(-1))
    }

    @Test
    fun `auto read code folds into the top level`() {
        assertEquals(3, seat.levelOf(0x1005020F))
    }

    @Test
    fun `toggle writes its on and off codes`() {
        assertEquals(268632321, recirc.toggleValue(true))
        assertEquals(268632322, recirc.toggleValue(false))
    }

    @Test
    fun `recirculation answers with codes - not 0-1`() {
        assertTrue(recirc.isActive(268632321))
        assertFalse(recirc.isActive(268632322))
        // a plain 1 is neither of the recirc codes: not on
        assertFalse(recirc.isActive(1))
        assertFalse(recirc.isActive(null))
    }

    @Test
    fun `slot is active only on its own restore value`() {
        assertTrue(memory.isActive(760219140))
        assertFalse(memory.isActive(760219139))
        assertFalse(memory.isActive(null))
        assertEquals(0, memory.slotIndexFromStatus(760219140))
        assertEquals(-1, memory.slotIndexFromStatus(760219139))
        assertEquals(-1, memory.slotIndexFromStatus(null))
    }
}
