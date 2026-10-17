package com.andrerinas.openheadunit.geely.hvac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto start/off write ladder (S008): entering auto tries the candidate entrances in
 * likelihood order — the manual property's own auto code (confirmed by the passenger seat,
 * rounds 4–5), then the MANUAL level codes ON the AUTO property (round 5: the car's own
 * catalog lists [0, 649, 650, 651] as its supported values), then the TIME knob, then the
 * legacy encodings. The wheel's auto is the stock SWITCH property (round 5: the stock AUTO
 * button writes it 1/0) — one rung, no level. The winner is the first rung the CAR confirms
 * (round 4: a write the port accepts can be silently ignored) — the verdict rule itself is
 * pinned by AutoConfirmationTest.
 */
class AutoStartAttemptsTest {

    private val seat = GeelyCarFunctions.seatSpec(Hvac.ZONE_DRIVER)
    private val wheel = GeelyCarFunctions.wheelSpec

    @Test
    fun `seat ladder leads with the manual auto code, then the manual codes on the auto property`() {
        assertEquals(
            listOf(
                CarWriteAttempt("auto-code", Hvac.PROP_SEAT_HEATING, Hvac.ZONE_DRIVER, Hvac.READ_AUTO_SEAT_HEAT),
                CarWriteAttempt("auto-levels", Hvac.PROP_AUTO_SEAT_HEATING, Hvac.ZONE_DRIVER, Hvac.SEAT_HEAT_LEVELS[2]),
                CarWriteAttempt("time", Hvac.PROP_AUTO_SEAT_HEATING_TIME, Hvac.ZONE_DRIVER, Hvac.AUTO_SEAT_HEAT_TIME_LEVELS[2]),
                CarWriteAttempt("time-global", Hvac.PROP_AUTO_SEAT_HEATING_TIME, Hvac.GLOBAL_AREA, Hvac.AUTO_SEAT_HEAT_TIME_LEVELS[2]),
                CarWriteAttempt("auto-plain", Hvac.PROP_AUTO_SEAT_HEATING, Hvac.ZONE_DRIVER, 2),
                CarWriteAttempt("auto-encoded", Hvac.PROP_AUTO_SEAT_HEATING, Hvac.ZONE_DRIVER, 268764418),
            ),
            seat.autoStartAttempts(level = 2)
        )
    }

    @Test
    fun `the auto-levels rung carries the manual-encoded code per level`() {
        for (level in 1..3) {
            val rung = seat.autoStartAttempts(level).first { it.label == "auto-levels" }
            assertEquals(Hvac.PROP_AUTO_SEAT_HEATING, rung.propertyId)
            assertEquals(Hvac.SEAT_HEAT_LEVELS[level], rung.value)
        }
    }

    @Test
    fun `wheel auto is the stock switch - one rung with the on-value`() {
        assertEquals(
            listOf(
                CarWriteAttempt("switch", Hvac.PROP_AUTO_STEERING_WHEEL_HEAT_SWITCH, Hvac.GLOBAL_AREA, 1),
            ),
            wheel.autoStartAttempts(level = 3)
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
                CarWriteAttempt("auto-off", Hvac.PROP_AUTO_STEERING_WHEEL_HEAT_SWITCH, Hvac.GLOBAL_AREA, 0),
                CarWriteAttempt("manual-off", Hvac.PROP_STEERING_WHEEL_HEAT, Hvac.GLOBAL_AREA, 0),
            ),
            wheel.autoOffAttempts()
        )
    }
}
