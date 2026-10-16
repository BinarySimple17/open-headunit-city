package com.andrerinas.openheadunit.geely.hvac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GeelyCarFunctions registry (F010) — the single source of tracked properties. These
 * invariants are what lets a new function be added in ONE place: the registry feeds status
 * reads, both ports' probes and the panel's button tree.
 */
class GeelyCarFunctionsTest {

    @Test
    fun `registry covers every model with the pinned status keys in order`() {
        for (model in Hvac.CarModel.values()) {
            assertEquals(Hvac.statusKeys(), GeelyCarFunctions.all(model).map { it.id })
        }
        assertEquals(14, GeelyCarFunctions.all(Hvac.CarModel.CITYRAY).size)
    }

    @Test
    fun `ids are unique`() {
        val ids = GeelyCarFunctions.all(Hvac.CarModel.ATLAS).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `byId resolves every status key`() {
        for (key in Hvac.statusKeys()) assertNotNull(key, GeelyCarFunctions.byId(key))
        assertNull(GeelyCarFunctions.byId("no:such:key"))
    }

    @Test
    fun `seat specs address the four zones of one property`() {
        assertEquals(intArrayOf(1, 4, 16, 64).toList(), GeelyCarFunctions.SEAT_ZONES.toList())
        for (zone in GeelyCarFunctions.SEAT_ZONES) {
            val spec = GeelyCarFunctions.seatSpec(zone)
            assertEquals(Hvac.PROP_SEAT_HEATING, spec.propertyId)
            assertEquals(zone, spec.areaId)
            assertEquals(FunctionKind.LEVELS, spec.kind)
            assertEquals(Hvac.seatKey(zone), spec.id)
            // The linked auto function is itself tracked and carries the AUTO table + TIME knob.
            val auto = spec.auto!!
            assertEquals(Hvac.PROP_AUTO_SEAT_HEATING, auto.propertyId)
            assertEquals(Hvac.seatAutoKey(zone), auto.id)
            assertEquals(Hvac.PROP_AUTO_SEAT_HEATING_TIME, auto.autoTimePropertyId)
            assertEquals(Hvac.AUTO_SEAT_HEAT_TIME_LEVELS[2], auto.autoTimeOnValue)
            assertNull(auto.auto)
            assertTrue(GeelyCarFunctions.byId(auto.id) != null)
        }
    }

    @Test
    fun `only the wheel links an auto function besides the seats`() {
        val wheel = GeelyCarFunctions.wheelSpec
        assertEquals(Hvac.KEY_WHEEL, wheel.id)
        assertEquals(Hvac.KEY_WHEEL_AUTO, wheel.auto!!.id)
        assertEquals(Hvac.PROP_AUTO_STEERING_WHEEL_HEAT_TIME, wheel.auto!!.autoTimePropertyId)
        assertEquals(Hvac.AUTO_WHEEL_HEAT_TIME_LEVELS[2], wheel.auto!!.autoTimeOnValue)
        assertNull(wheel.auto!!.auto)
        assertNull(GeelyCarFunctions.defrostRearSpec.auto)
        assertNull(GeelyCarFunctions.defrostMaxSpec.auto)
        assertNull(GeelyCarFunctions.recirculationSpec.auto)
    }

    @Test
    fun `front defrost property id follows the model`() {
        for (model in Hvac.CarModel.values()) {
            assertEquals(model.defrostFrontPropertyId, GeelyCarFunctions.defrostFrontSpec(model).propertyId)
        }
        // Toggle semantics: defrost 0/1, recirculation with its ON/OFF codes.
        assertEquals(FunctionKind.TOGGLE, GeelyCarFunctions.defrostFrontSpec(Hvac.CarModel.CITYRAY).kind)
        assertEquals(1, GeelyCarFunctions.defrostRearSpec.onValue)
        assertEquals(Hvac.CIRCULATION_ON, GeelyCarFunctions.recirculationSpec.onValue)
        assertEquals(Hvac.CIRCULATION_OFF, GeelyCarFunctions.recirculationSpec.offValue)
    }

    @Test
    fun `spec folds levels exactly like the historical key-based logic`() {
        val seat = GeelyCarFunctions.seatSpec(Hvac.ZONE_DRIVER)
        // Encoded, plain ordinal, auto read code, garbage — mirror of HvacLogicTest.
        assertEquals(2, seat.levelOf(268763650))
        assertEquals(1, seat.levelOf(1))
        assertEquals(3, seat.levelOf(Hvac.READ_AUTO_SEAT_HEAT))
        assertEquals(0, seat.levelOf(999999))
        assertEquals(0, seat.levelOf(null))
        assertTrue(seat.isActive(268763649))
        assertFalse(seat.isActive(0))
        // Toggles never carry a level.
        assertEquals(0, GeelyCarFunctions.recirculationSpec.levelOf(Hvac.CIRCULATION_ON))
        assertTrue(GeelyCarFunctions.recirculationSpec.isActive(Hvac.CIRCULATION_ON))
        assertFalse(GeelyCarFunctions.recirculationSpec.isActive(Hvac.CIRCULATION_OFF))
        assertFalse(GeelyCarFunctions.recirculationSpec.isActive(null))
    }

    @Test
    fun `next level value cycles off to l1 and wraps off the top`() {
        val wheel = GeelyCarFunctions.wheelSpec
        assertEquals(269025537, wheel.nextLevelValue(0))
        assertEquals(269025539, wheel.nextLevelValue(2))
        assertEquals(0, wheel.nextLevelValue(3))
        // A tap on a toggle flips between its on and off values.
        val recirc = GeelyCarFunctions.recirculationSpec
        assertEquals(Hvac.CIRCULATION_ON, recirc.toggleValue(turnOn = true))
        assertEquals(Hvac.CIRCULATION_OFF, recirc.toggleValue(turnOn = false))
    }

    @Test
    fun `tracked property ids are the distinct set plus all model defrost ids`() {
        val expected = setOf(
            Hvac.PROP_SEAT_HEATING, Hvac.PROP_AUTO_SEAT_HEATING,
            Hvac.PROP_STEERING_WHEEL_HEAT, Hvac.PROP_AUTO_STEERING_WHEEL_HEAT,
            Hvac.PROP_DEFROST_REAR, Hvac.PROP_DEFROST_FRONT_MAX, Hvac.PROP_CIRCULATION,
            // Auto companions (S008): not polled, but probed and catalog-dumped.
            Hvac.PROP_AUTO_SEAT_HEATING_TIME,
            Hvac.PROP_AUTO_STEERING_WHEEL_HEAT_TIME,
            Hvac.PROP_AUTO_STEERING_WHEEL_HEAT_SWITCH,
        ) + Hvac.CarModel.values().map { it.defrostFrontPropertyId }
        assertEquals(expected, GeelyCarFunctions.trackedPropertyIds())
    }

    @Test
    fun `probe list preserves the historical order and labels`() {
        val probes = GeelyCarFunctions.probes()
        val labels = probes.map { it.label }
        assertEquals(
            listOf(
                "seat", "seat_auto", "seat", "seat_auto", "seat", "seat_auto", "seat", "seat_auto",
                "wheel", "wheel_auto",
                "seat_auto_time", "seat_auto_time", "seat_auto_time", "seat_auto_time",
                "wheel_auto_time", "wheel_auto_switch",
                "defrost_front@CITYRAY", "defrost_front@PREFACE", "defrost_front@ATLAS",
                "defrost_rear", "defrost_max", "recirc",
            ),
            labels
        )
        // Every probe addresses the bus like the panel does; companion probes are bare (no spec).
        assertEquals(Hvac.GLOBAL_AREA, probes.last().areaId)
        assertEquals(Hvac.ZONE_DRIVER, probes.first().areaId)
        assertEquals(Hvac.PROP_AUTO_SEAT_HEATING, probes[1].propertyId)
        assertNull(probes[10].spec)
        assertEquals(Hvac.PROP_AUTO_SEAT_HEATING_TIME, probes[10].propertyId)
        assertEquals(Hvac.ZONE_DRIVER, probes[10].areaId)
        assertNotNull(probes[0].spec)
    }
}
