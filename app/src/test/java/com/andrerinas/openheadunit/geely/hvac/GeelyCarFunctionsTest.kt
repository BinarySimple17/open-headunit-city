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
        assertEquals(11, GeelyCarFunctions.all(Hvac.CarModel.CITYRAY).size)
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
            // The car's own auto read code folds display-side (the stock AUTO button).
            assertEquals(Hvac.READ_AUTO_SEAT_HEAT, spec.autoReadCode)
            assertTrue(GeelyCarFunctions.byId(spec.id) != null)
        }
    }

    @Test
    fun `the F008 auto keys are gone from the registry`() {
        // Planner decision 2026-10-17 (rounds 4-6, конспект §2.10): the auto-heat entrances
        // left the registry; only the display fold of the car's own auto code remains.
        assertTrue(GeelyCarFunctions.all(Hvac.CarModel.CITYRAY).none { "auto" in it.id })
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
            Hvac.PROP_SEAT_HEATING, Hvac.PROP_STEERING_WHEEL_HEAT,
            Hvac.PROP_DEFROST_REAR, Hvac.PROP_DEFROST_FRONT_MAX, Hvac.PROP_CIRCULATION,
            Hvac.PROP_SEAT_MEMORY,
        ) + Hvac.CarModel.values().map { it.defrostFrontPropertyId }
        assertEquals(expected, GeelyCarFunctions.trackedPropertyIds())
    }

    @Test
    fun `seat-heat specs carry the F015 tap window, the rest carry none`() {
        for (zone in GeelyCarFunctions.SEAT_ZONES) {
            assertEquals(Hvac.TAP_OFF_WINDOW_MS, GeelyCarFunctions.seatSpec(zone).tapOffWindowMs)
        }
        assertNull(GeelyCarFunctions.wheelSpec.tapOffWindowMs)
        assertNull(GeelyCarFunctions.recirculationSpec.tapOffWindowMs)
        assertNull(GeelyCarFunctions.defrostRearSpec.tapOffWindowMs)
    }

    @Test
    fun `seat-memory slots follow the model ladder and activate only on the car's read`() {
        val cityray = GeelyCarFunctions.memorySlotSpecs(Hvac.CarModel.CITYRAY)
        assertEquals(listOf("memory:1", "memory:2"), cityray.map { it.id })
        assertEquals(Hvac.PROP_SEAT_MEMORY, cityray[0].propertyId)
        assertEquals(Hvac.SEAT_MEMORY_AREA, cityray[0].areaId)
        assertEquals(FunctionKind.SLOT, cityray[0].kind)
        assertEquals(760218882, cityray[0].slotValue)
        assertEquals(760218883, cityray[1].slotValue)
        // The ACTIVE verdict comes only from the car's read, never from a tap.
        assertTrue(cityray[0].isActive(760218882))
        assertFalse(cityray[0].isActive(760218883))
        assertFalse(cityray[0].isActive(null))
        // ATLAS: the §2e ladder of three, the panel's two buttons carry its first two values.
        val atlas = GeelyCarFunctions.memorySlotSpecs(Hvac.CarModel.ATLAS)
        assertEquals(760219140, atlas[0].slotValue)
        assertEquals(760219139, atlas[1].slotValue)
        // PREFACE: no confirmed ladder — the specs read but never command.
        val preface = GeelyCarFunctions.memorySlotSpecs(Hvac.CarModel.PREFACE)
        assertNull(preface[0].slotValue)
        assertNull(preface[1].slotValue)
        assertFalse(preface[0].isActive(760218882))
    }

    @Test
    fun `probe list preserves the historical order and labels`() {
        val probes = GeelyCarFunctions.probes()
        val labels = probes.map { it.label }
        assertEquals(
            listOf(
                "seat", "seat", "seat", "seat",
                "wheel",
                "defrost_front@CITYRAY", "defrost_front@PREFACE", "defrost_front@ATLAS",
                "defrost_rear", "defrost_max", "recirc", "seat_memory",
            ),
            labels
        )
        // Every probe addresses the bus like the panel does and carries its spec.
        assertEquals(Hvac.SEAT_MEMORY_AREA, probes.last().areaId)
        assertEquals(Hvac.PROP_SEAT_MEMORY, probes.last().propertyId)
        assertEquals(Hvac.GLOBAL_AREA, probes[probes.size - 2].areaId)
        assertEquals(Hvac.ZONE_DRIVER, probes.first().areaId)
        assertEquals(Hvac.PROP_SEAT_HEATING, probes[0].propertyId)
        assertNotNull(probes[0].spec)
        assertNotNull(probes[4].spec)
        assertEquals(Hvac.PROP_STEERING_WHEEL_HEAT, probes[4].propertyId)
    }

}
