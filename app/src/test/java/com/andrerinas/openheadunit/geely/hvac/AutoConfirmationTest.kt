package com.andrerinas.openheadunit.geely.hvac

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car's-verdict rule of the auto ladder (S008 round 4): a write the port accepted is not
 * an entrance — the driver seat silently ignored the auto code twice while the port reported
 * ok. A rung counts only when the zone answers in auto ([CarFunctionSpec.readsAuto]), an
 * exit only when the AUTO property answers off again ([CarFunctionSpec.readsAutoOff]).
 * Pinned to the round-4 readings from the CITYRAY.
 */
class AutoConfirmationTest {

    private val seat = GeelyCarFunctions.seatSpec(Hvac.ZONE_PASSENGER)
    private val wheel = GeelyCarFunctions.wheelSpec
    private val code = Hvac.READ_AUTO_SEAT_HEAT

    @Test
    fun `the confirmed round-4 passenger entrance reads as auto on both properties`() {
        assertTrue(seat.readsAuto(manualRaw = code, autoRaw = code))
    }

    @Test
    fun `a known auto level on the auto property reads as auto`() {
        assertTrue(seat.readsAuto(manualRaw = 0, autoRaw = Hvac.AUTO_SEAT_HEAT_LEVELS[2]))
    }

    @Test
    fun `the mirrored manual level never reads as auto`() {
        // Round 4: manual L1 mirrored onto the auto property as base|1 (seat_auto:1 0 -> 268763649).
        assertFalse(seat.readsAuto(manualRaw = Hvac.SEAT_HEAT_LEVELS[1], autoRaw = Hvac.SEAT_HEAT_LEVELS[1]))
    }

    @Test
    fun `off reads and absent answers do not read as auto`() {
        assertFalse(seat.readsAuto(manualRaw = 0, autoRaw = 0))
        assertFalse(seat.readsAuto(manualRaw = null, autoRaw = null))
    }

    @Test
    fun `wheel auto is a table answer only - it has no read code`() {
        assertFalse(wheel.readsAuto(manualRaw = Hvac.WHEEL_HEAT_LEVELS[1], autoRaw = 0))
        assertTrue(wheel.readsAuto(manualRaw = 0, autoRaw = Hvac.AUTO_WHEEL_HEAT_LEVELS[1]))
    }

    @Test
    fun `exit confirms on the auto property answering off`() {
        assertTrue(seat.readsAutoOff(autoRaw = 0))
        assertFalse(seat.readsAutoOff(autoRaw = null))
        assertFalse(seat.readsAutoOff(autoRaw = Hvac.AUTO_SEAT_HEAT_LEVELS[1]))
    }

    @Test
    fun `the manual property may keep answering the auto code after an exit`() {
        // Round 4: after the passenger exit the auto property went 0 while the manual one
        // stayed on the code — which is why exit confirmation reads the AUTO property only.
        // The enter/exit branch then sees the zone as in auto and long-press re-runs the
        // (harmless) off path instead of a fresh entrance ladder.
        assertTrue(seat.readsAutoOff(autoRaw = 0))
        assertTrue(seat.readsAuto(manualRaw = code, autoRaw = 0))
    }
}
