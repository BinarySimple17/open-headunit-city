package com.andrerinas.openheadunit.geely.hvac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto start/off write ladder (S008): the ГУ round of 2026-10-12 rejected the
 * base|level write on the dedicated AUTO properties, so entering auto now tries the
 * candidate entrances in likelihood order — the manual property's own auto code, the TIME
 * knob, then the AUTO property in both encodings. The winner is the first rung the CAR
 * confirms (round 4: a write the port accepts can be silently ignored) — the verdict rule
 * itself is pinned by AutoConfirmationTest.
 */
class AutoStartAttemptsTest {

    private val seat = GeelyCarFunctions.seatSpec(Hvac.ZONE_DRIVER)
    private val wheel = GeelyCarFunctions.wheelSpec

    @Test
    fun `seat ladder leads with the manual auto code, then time, then the auto property`() {
        assertEquals(
            listOf(
                CarWriteAttempt("auto-code", Hvac.PROP_SEAT_HEATING, Hvac.ZONE_DRIVER, Hvac.READ_AUTO_SEAT_HEAT),
                CarWriteAttempt("time", Hvac.PROP_AUTO_SEAT_HEATING_TIME, Hvac.ZONE_DRIVER, Hvac.AUTO_SEAT_HEAT_TIME_LEVELS[2]),
                CarWriteAttempt("time-global", Hvac.PROP_AUTO_SEAT_HEATING_TIME, Hvac.GLOBAL_AREA, Hvac.AUTO_SEAT_HEAT_TIME_LEVELS[2]),
                CarWriteAttempt("auto-plain", Hvac.PROP_AUTO_SEAT_HEATING, Hvac.ZONE_DRIVER, 2),
                CarWriteAttempt("auto-encoded", Hvac.PROP_AUTO_SEAT_HEATING, Hvac.ZONE_DRIVER, 268764418),
            ),
            seat.autoStartAttempts(level = 2)
        )
    }

    @Test
    fun `wheel ladder has no auto code and no duplicated global time attempt`() {
        val attempts = wheel.autoStartAttempts(level = 1)
        assertEquals(
            listOf(
                CarWriteAttempt("time", Hvac.PROP_AUTO_STEERING_WHEEL_HEAT_TIME, Hvac.GLOBAL_AREA, Hvac.AUTO_WHEEL_HEAT_TIME_LEVELS[2]),
                CarWriteAttempt("auto-plain", Hvac.PROP_AUTO_STEERING_WHEEL_HEAT, Hvac.GLOBAL_AREA, 1),
                CarWriteAttempt("auto-encoded", Hvac.PROP_AUTO_STEERING_WHEEL_HEAT, Hvac.GLOBAL_AREA, 269025793),
            ),
            attempts
        )
    }

    @Test
    fun `ladder covers the whole level range with encoded table values`() {
        for (level in 1..3) {
            val encoded = seat.autoStartAttempts(level).last()
            assertEquals(Hvac.AUTO_SEAT_HEAT_LEVELS[level], encoded.value)
        }
    }

    @Test
    fun `functions without auto have empty ladders`() {
        assertTrue(GeelyCarFunctions.defrostRearSpec.autoStartAttempts(1).isEmpty())
        assertTrue(GeelyCarFunctions.defrostRearSpec.autoOffAttempts().isEmpty())
    }

    @Test
    fun `off ladder returns every entrance to zero, best-effort`() {
        assertEquals(
            listOf(
                CarWriteAttempt("time-off", Hvac.PROP_AUTO_SEAT_HEATING_TIME, Hvac.ZONE_DRIVER, 0),
                CarWriteAttempt("time-off-global", Hvac.PROP_AUTO_SEAT_HEATING_TIME, Hvac.GLOBAL_AREA, 0),
                CarWriteAttempt("auto-off", Hvac.PROP_AUTO_SEAT_HEATING, Hvac.ZONE_DRIVER, 0),
                CarWriteAttempt("manual-off", Hvac.PROP_SEAT_HEATING, Hvac.ZONE_DRIVER, 0),
            ),
            seat.autoOffAttempts()
        )
        assertEquals(
            listOf(
                CarWriteAttempt("time-off", Hvac.PROP_AUTO_STEERING_WHEEL_HEAT_TIME, Hvac.GLOBAL_AREA, 0),
                CarWriteAttempt("auto-off", Hvac.PROP_AUTO_STEERING_WHEEL_HEAT, Hvac.GLOBAL_AREA, 0),
                CarWriteAttempt("manual-off", Hvac.PROP_STEERING_WHEEL_HEAT, Hvac.GLOBAL_AREA, 0),
            ),
            wheel.autoOffAttempts()
        )
    }
}
