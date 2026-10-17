package com.andrerinas.openheadunit.geely.hvac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HvacLogicTest {

    //region Level normalization

    @Test
    fun `seat heat reads answer with the encoded levels themselves`() {
        assertEquals(0, Hvac.normalizeLevel(0, Hvac.SEAT_HEAT_LEVELS, Hvac.READ_AUTO_SEAT_HEAT))
        assertEquals(268763649, Hvac.normalizeLevel(268763649, Hvac.SEAT_HEAT_LEVELS, Hvac.READ_AUTO_SEAT_HEAT))
        assertEquals(268763651, Hvac.normalizeLevel(268763651, Hvac.SEAT_HEAT_LEVELS, Hvac.READ_AUTO_SEAT_HEAT))
    }

    @Test
    fun `firmware may answer seat heat with a plain ordinal`() {
        assertEquals(268763649, Hvac.normalizeLevel(1, Hvac.SEAT_HEAT_LEVELS, Hvac.READ_AUTO_SEAT_HEAT))
        assertEquals(268763651, Hvac.normalizeLevel(3, Hvac.SEAT_HEAT_LEVELS, Hvac.READ_AUTO_SEAT_HEAT))
    }

    @Test
    fun `seat heat auto read code folds into the top level`() {
        assertEquals(268763651, Hvac.normalizeLevel(0x1005020F, Hvac.SEAT_HEAT_LEVELS, Hvac.READ_AUTO_SEAT_HEAT))
    }

    @Test
    fun `wheel heat has no auto read code`() {
        assertEquals(269025539, Hvac.normalizeLevel(3, Hvac.WHEEL_HEAT_LEVELS))
        assertEquals(0, Hvac.normalizeLevel(0x1005020F, Hvac.WHEEL_HEAT_LEVELS))
    }

    @Test
    fun `garbage reads count as off`() {
        assertEquals(0, Hvac.normalizeLevel(-5, Hvac.SEAT_HEAT_LEVELS, Hvac.READ_AUTO_SEAT_HEAT))
        assertEquals(0, Hvac.normalizeLevel(0x1005010F, Hvac.SEAT_HEAT_LEVELS, Hvac.READ_AUTO_SEAT_HEAT))
        assertEquals(0, Hvac.normalizeLevel(999999, Hvac.SEAT_HEAT_LEVELS, Hvac.READ_AUTO_SEAT_HEAT))
    }

    //endregion

    //region Level cycling

    @Test
    fun `unknown or off state starts at level 1`() {
        assertEquals(1, Hvac.nextLevelIndex(0, Hvac.SEAT_HEAT_LEVELS))
        assertEquals(1, Hvac.nextLevelIndex(42, Hvac.SEAT_HEAT_LEVELS))
    }

    @Test
    fun `levels cycle up and wrap to off`() {
        assertEquals(2, Hvac.nextLevelIndex(268763649, Hvac.SEAT_HEAT_LEVELS))
        assertEquals(3, Hvac.nextLevelIndex(268763650, Hvac.SEAT_HEAT_LEVELS))
        assertEquals(0, Hvac.nextLevelIndex(268763651, Hvac.SEAT_HEAT_LEVELS))
        assertEquals(3, Hvac.nextLevelIndex(269025538, Hvac.WHEEL_HEAT_LEVELS))
        assertEquals(0, Hvac.nextLevelIndex(269025539, Hvac.WHEEL_HEAT_LEVELS))
    }

    //endregion

    //region Toggle semantics

    @Test
    fun `defrost properties are binary`() {
        assertTrue(Hvac.isDefrostOn(1))
        assertFalse(Hvac.isDefrostOn(0))
        assertFalse(Hvac.isDefrostOn(-1))
    }

    @Test
    fun `recirculation answers with on and off codes not 0 and 1`() {
        assertTrue(Hvac.isRecirculationOn(268632321))
        assertFalse(Hvac.isRecirculationOn(268632322))
        assertFalse(Hvac.isRecirculationOn(0))
    }

    //endregion

    //region Seat zones and model mapping

    @Test
    fun `seat popup zones are driver passenger rear left rear right`() {
        val zones = "1/4/16/64"
        assertEquals("1/4/16/64", zones)
        assertEquals(1, Hvac.ZONE_DRIVER)
        assertEquals(4, Hvac.ZONE_PASSENGER)
        assertEquals(16, Hvac.ZONE_REAR_LEFT)
        assertEquals(64, Hvac.ZONE_REAR_RIGHT)
        // All four seats are zones of one and the same property.
        assertEquals(Hvac.PROP_SEAT_HEATING, 268763648)
    }

    @Test
    fun `front defrost property id follows the car model`() {
        assertEquals(269755136, Hvac.CarModel.CITYRAY.defrostFrontPropertyId)
        assertEquals(269753088, Hvac.CarModel.PREFACE.defrostFrontPropertyId)
        assertEquals(269027328, Hvac.CarModel.ATLAS.defrostFrontPropertyId)
    }

    //endregion

    //region Status scheme (buttons mirror real property reads)

    @Test
    fun `status keys cover all buttons in panel order`() {
        assertEquals(
            listOf(
                "seat:1", "seat:4", "seat:16", "seat:64",
                "seat_auto:1", "seat_auto:4", "seat_auto:16", "seat_auto:64",
                "wheel", "wheel_auto", "defrost_front", "defrost_rear", "defrost_max", "recirc"
            ),
            Hvac.statusKeys()
        )
        assertEquals("seat:${Hvac.ZONE_REAR_LEFT}", Hvac.seatKey(Hvac.ZONE_REAR_LEFT))
        assertEquals("seat_auto:${Hvac.ZONE_REAR_RIGHT}", Hvac.seatAutoKey(Hvac.ZONE_REAR_RIGHT))
        assertEquals(Hvac.ZONE_REAR_RIGHT, Hvac.seatZoneOfKey("seat:64"))
        assertEquals(Hvac.ZONE_REAR_RIGHT, Hvac.seatZoneOfKey("seat_auto:64"))
    }

    @Test
    fun `read status asks the port for every tracked property`() {
        val asked = mutableListOf<Pair<Int, Int>>()
        val answers = mapOf(
            Hvac.PROP_SEAT_HEATING to 268763650,
            Hvac.PROP_AUTO_SEAT_HEATING to 0,
            Hvac.PROP_STEERING_WHEEL_HEAT to 0,
            Hvac.PROP_AUTO_STEERING_WHEEL_HEAT to 0,
            Hvac.CarModel.CITYRAY.defrostFrontPropertyId to 1,
            Hvac.PROP_DEFROST_REAR to 0,
            Hvac.PROP_DEFROST_FRONT_MAX to 0,
            Hvac.PROP_CIRCULATION to Hvac.CIRCULATION_ON,
        )
        val port = object : CarFunctionPort {
            override val available = true
            override fun getInt(propertyId: Int, areaId: Int): Int? {
                asked += propertyId to areaId
                return answers[propertyId]
            }
            override fun setInt(propertyId: Int, areaId: Int, value: Int) = true
        }
        val status = Hvac.readStatus(port, Hvac.CarModel.CITYRAY)
        // Four seat zones + their auto, wheel + its auto, three defrosts, recirculation.
        assertEquals(14, asked.size)
        assertEquals(4, asked.count { it.first == Hvac.PROP_SEAT_HEATING })
        assertEquals(4, asked.count { it.first == Hvac.PROP_AUTO_SEAT_HEATING })
        assertEquals(Hvac.GLOBAL_AREA, asked.single { it.first == Hvac.PROP_STEERING_WHEEL_HEAT }.second)
        assertEquals(268763650, status["seat:${Hvac.ZONE_DRIVER}"])
        assertEquals(0, status["seat_auto:${Hvac.ZONE_DRIVER}"])
        assertEquals(0, status[Hvac.KEY_WHEEL])
        assertEquals(1, status[Hvac.KEY_DEFROST_FRONT])
        assertEquals(Hvac.CIRCULATION_ON, status[Hvac.KEY_RECIRC])
    }

    @Test
    fun `changed keys reports only differing values`() {
        val prev = mapOf<String, Int?>("wheel" to 269025537, "recirc" to Hvac.CIRCULATION_OFF, "defrost_rear" to null)
        val next = mapOf<String, Int?>("wheel" to 269025537, "recirc" to Hvac.CIRCULATION_ON, "defrost_rear" to null)
        assertEquals(listOf("recirc"), Hvac.changedKeys(prev, next))
    }

    @Test
    fun `button level folds a raw read into the display ordinal`() {
        // Encoded levels answer as themselves; the badge digit is the ordinal, not the code.
        assertEquals(2, Hvac.buttonLevel("seat:${Hvac.ZONE_PASSENGER}", 268763650))
        assertEquals(3, Hvac.buttonLevel(Hvac.KEY_WHEEL, 269025539))
        // Plain ordinals and the auto-read code fold in as well.
        assertEquals(1, Hvac.buttonLevel("seat:${Hvac.ZONE_DRIVER}", 1))
        assertEquals(3, Hvac.buttonLevel("seat:${Hvac.ZONE_REAR_LEFT}", Hvac.READ_AUTO_SEAT_HEAT))
        assertEquals(0, Hvac.buttonLevel(Hvac.KEY_WHEEL, 0))
        assertEquals(0, Hvac.buttonLevel("seat:${Hvac.ZONE_REAR_RIGHT}", 999999))
        assertEquals(0, Hvac.buttonLevel(Hvac.KEY_WHEEL, null))
        // Non-level buttons carry no badge digit.
        assertEquals(0, Hvac.buttonLevel(Hvac.KEY_RECIRC, Hvac.CIRCULATION_ON))
    }

    @Test
    fun `auto keys fold their own encoded levels`() {
        assertEquals(1, Hvac.buttonLevel("seat_auto:${Hvac.ZONE_DRIVER}", 268764417))
        assertEquals(3, Hvac.buttonLevel("seat_auto:${Hvac.ZONE_REAR_LEFT}", 268764419))
        // Round 5: the wheel auto key reads the stock SWITCH (0/1) — a toggle carries no badge digit.
        assertEquals(0, Hvac.buttonLevel(Hvac.KEY_WHEEL_AUTO, 1))
        assertEquals(0, Hvac.buttonLevel(Hvac.KEY_WHEEL_AUTO, 269025794))
        // Plain ordinals work too; garbage and null count as off.
        assertEquals(2, Hvac.buttonLevel("seat_auto:${Hvac.ZONE_PASSENGER}", 2))
        assertEquals(0, Hvac.buttonLevel("seat_auto:${Hvac.ZONE_REAR_RIGHT}", 268763651))
        assertEquals(0, Hvac.buttonLevel(Hvac.KEY_WHEEL_AUTO, null))
        // The manual auto-read code means nothing on the auto property.
        assertEquals(0, Hvac.buttonLevel("seat_auto:${Hvac.ZONE_DRIVER}", Hvac.READ_AUTO_SEAT_HEAT))
    }

    @Test
    fun `button active mirrors the real function state`() {
        assertTrue(Hvac.buttonActive("seat:${Hvac.ZONE_DRIVER}", 268763649))
        assertTrue(Hvac.buttonActive(Hvac.KEY_WHEEL, 269025539))
        assertFalse(Hvac.buttonActive(Hvac.KEY_WHEEL, 0))
        assertFalse(Hvac.buttonActive(Hvac.KEY_WHEEL, null))
        assertTrue(Hvac.buttonActive(Hvac.KEY_DEFROST_FRONT, 1))
        assertFalse(Hvac.buttonActive(Hvac.KEY_DEFROST_FRONT, 0))
        assertFalse(Hvac.buttonActive(Hvac.KEY_DEFROST_REAR, null))
        assertTrue(Hvac.buttonActive(Hvac.KEY_RECIRC, Hvac.CIRCULATION_ON))
        assertFalse(Hvac.buttonActive(Hvac.KEY_RECIRC, Hvac.CIRCULATION_OFF))
        assertTrue(Hvac.buttonActive("seat_auto:${Hvac.ZONE_DRIVER}", 268764418))
        // Round 5: the wheel auto key answers through the stock switch (on = 1).
        assertTrue(Hvac.buttonActive(Hvac.KEY_WHEEL_AUTO, 1))
        assertFalse(Hvac.buttonActive(Hvac.KEY_WHEEL_AUTO, 269025793))
        assertFalse(Hvac.buttonActive(Hvac.KEY_WHEEL_AUTO, 0))
    }

    @Test
    fun `display level prefers auto while it runs`() {
        // While auto is on, its level wins: the masked manual level must not leak into the
        // color (the main property answers the auto read code, which folds to L3 — the
        // "configured 1, shown 3" report).
        assertEquals(3, Hvac.displayLevel(3, 0))
        assertEquals(1, Hvac.displayLevel(3, 1))
        assertEquals(2, Hvac.displayLevel(2, 0))
    }

    @Test
    fun `next level ordinal cycles from off to l1 and wraps off the top`() {
        assertEquals(1, Hvac.nextLevelOrdinal(0, Hvac.SEAT_HEAT_LEVELS))
        assertEquals(2, Hvac.nextLevelOrdinal(1, Hvac.SEAT_HEAT_LEVELS))
        assertEquals(3, Hvac.nextLevelOrdinal(2, Hvac.SEAT_HEAT_LEVELS))
        assertEquals(0, Hvac.nextLevelOrdinal(3, Hvac.SEAT_HEAT_LEVELS))
        // Unknown status behaves like off: the tap turns the function on at L1.
        assertEquals(1, Hvac.nextLevelOrdinal(-1, Hvac.WHEEL_HEAT_LEVELS))
    }

    //endregion
}
