package com.geely.mockcar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockCarStateTest {

    @Test
    fun `seat heating accepts encoded levels and stores them back`() {
        val state = MockCarState()
        assertTrue(state.set(268763648, 1, 268763649))
        assertEquals(268763649, state.get(268763648, 1))
        assertTrue(state.set(268763648, 1, 0))
        assertEquals(0, state.get(268763648, 1))
    }

    @Test
    fun `seat heating folds plain ordinals into encoded levels`() {
        val state = MockCarState()
        assertTrue(state.set(268763648, 4, 2))
        assertEquals(268763650, state.get(268763648, 4))
    }

    @Test
    fun `rear seat zones 16 and 64 are accepted`() {
        val state = MockCarState()
        assertTrue(state.set(268763648, 16, 268763651))
        assertTrue(state.set(268763648, 64, 268763649))
        assertEquals(268763651, state.get(268763648, 16))
        assertEquals(268763649, state.get(268763648, 64))
    }

    @Test
    fun `unknown zones and values are rejected like a real car`() {
        val state = MockCarState()
        assertFalse(state.set(268763648, 2, 268763649)) // row-1 center is not a mock zone
        assertFalse(state.set(268763648, 1, 7)) // garbage level
        assertFalse(state.set(268763648, 1, 268763652)) // out of the encoded set
        assertEquals(0, state.get(268763648, 1))
    }

    @Test
    fun `recirculation only accepts the on and off codes`() {
        val state = MockCarState()
        assertTrue(state.set(268632320, Int.MIN_VALUE, 268632321))
        assertFalse(state.set(268632320, Int.MIN_VALUE, 0))
        assertFalse(state.set(268632320, Int.MIN_VALUE, 1))
        assertEquals(268632321, state.get(268632320, Int.MIN_VALUE))
    }

    @Test
    fun `defrost properties are binary and model ids are known`() {
        val state = MockCarState()
        for (id in intArrayOf(268698368, 268698112, 269755136, 269753088, 269027328)) {
            assertTrue(state.set(id, Int.MIN_VALUE, 1))
            assertEquals(1, state.get(id, Int.MIN_VALUE))
            assertFalse(state.set(id, Int.MIN_VALUE, 5))
        }
    }

    @Test
    fun `unset values read as zero`() {
        assertEquals(0, MockCarState().get(268763648, 1))
    }

    @Test
    fun `clear resets everything`() {
        val state = MockCarState()
        state.set(268763648, 1, 268763649)
        state.clear()
        assertEquals(0, state.get(268763648, 1))
        assertTrue(state.snapshot().isEmpty())
    }

    @Test
    fun `unknown property is rejected`() {
        assertFalse(MockCarState().set(12345, 1, 1))
        assertNull(MockCarSpec.normalize(12345, 1))
    }

    @Test
    fun `auto seat heating accepts encoded levels per zone`() {
        val state = MockCarState()
        assertTrue(state.set(268764416, 1, 268764417))
        assertTrue(state.set(268764416, 64, 2))
        assertEquals(268764417, state.get(268764416, 1))
        assertEquals(268764418, state.get(268764416, 64))
        assertTrue(state.set(268764416, 1, 0))
        assertEquals(0, state.get(268764416, 1))
        // Row-1 center is not a zone; garbage levels are rejected.
        assertFalse(state.set(268764416, 2, 268764417))
        assertFalse(state.set(268764416, 1, 268763651))
    }

    @Test
    fun `auto wheel heating accepts encoded levels globally`() {
        val state = MockCarState()
        assertTrue(state.set(269025792, Int.MIN_VALUE, 269025794))
        assertEquals(269025794, state.get(269025792, Int.MIN_VALUE))
        assertTrue(state.set(269025792, Int.MIN_VALUE, 0))
        assertEquals(0, state.get(269025792, Int.MIN_VALUE))
        assertFalse(state.set(269025792, Int.MIN_VALUE, 269025537)) // manual level is not an auto value
    }

    @Test
    fun `while auto is on the main seat property answers the auto code`() {
        val state = MockCarState()
        state.set(268763648, 4, 268763650) // manual L2 stored
        assertEquals(268763650, state.get(268763648, 4))
        state.set(268764416, 4, 268764418) // auto L2 on
        assertEquals(268763663, state.get(268763648, 4))
        // Other zones stay manual; auto off restores the manual read.
        assertEquals(0, state.get(268763648, 1))
        state.set(268764416, 4, 0)
        assertEquals(268763650, state.get(268763648, 4))
    }
}
