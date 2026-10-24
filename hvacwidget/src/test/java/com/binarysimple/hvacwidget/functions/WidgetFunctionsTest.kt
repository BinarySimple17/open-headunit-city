package com.binarysimple.hvacwidget.functions

import com.binarysimple.hvacwidget.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The registry is the single source of truth — its invariants are the widget's invariants. */
class WidgetFunctionsTest {

    @Test
    fun `ids are unique per model`() {
        for (model in WidgetFunctions.Model.entries) {
            val ids = WidgetFunctions.all(model).map { it.id }
            assertEquals("$model: duplicate ids", ids.size, ids.toSet().size)
        }
    }

    @Test
    fun `non-slot property and area pairs are unique per model`() {
        for (model in WidgetFunctions.Model.entries) {
            val addresses = WidgetFunctions.all(model)
                .filter { it.kind != FunctionKind.SLOT }
                .map { it.propertyId to it.areaId }
            assertEquals("$model: duplicate property/area", addresses.size, addresses.toSet().size)
        }
    }

    @Test
    fun `slot specs share the memory property but differ in their restore values`() {
        // The memory buttons intentionally write different VALUES to the same property/area
        // (the car's restore codes, base|N) — uniqueness there is per value, not per address.
        for (model in WidgetFunctions.Model.entries) {
            val slots = WidgetFunctions.all(model).filter { it.kind == FunctionKind.SLOT }
            val values = slots.map { it.slots!![0] }
            assertEquals("$model: duplicate slot values", values.size, values.toSet().size)
            assertTrue(
                "$model: every slot spec shares the memory address",
                slots.all { it.propertyId == WidgetFunctions.PROP_SEAT_MEMORY && it.areaId == WidgetFunctions.SEAT_MEMORY_AREA },
            )
        }
    }

    @Test
    fun `every function is bound to a button - label and view slot`() {
        for (model in WidgetFunctions.Model.entries) {
            for (spec in WidgetFunctions.all(model)) {
                assertTrue("$model/${spec.id}: no label", spec.labelRes != 0)
                assertTrue("$model/${spec.id}: no view slot", spec.viewId != 0)
            }
        }
    }

    @Test
    fun `every layout slot is covered - climate always, memory per model ladder`() {
        val climateSlots = setOf(
            R.id.btn_wheel, R.id.btn_seat_driver, R.id.btn_seat_passenger,
            R.id.btn_defrost_front, R.id.btn_defrost_rear, R.id.btn_recirc,
        )
        for (model in WidgetFunctions.Model.entries) {
            val specs = WidgetFunctions.all(model)
            val slots = specs.map { it.viewId }
            assertEquals("$model: duplicate view slots", slots.size, slots.toSet().size)
            assertEquals(
                "$model: climate slots not covered exactly once",
                climateSlots, slots.toSet() intersect climateSlots,
            )
            // memory buttons = exactly the model's ladder, taken from the shared slot list
            val memory = specs.filter { it.kind == FunctionKind.SLOT }
            assertEquals(model.memorySlots.size, memory.size)
            assertEquals(
                WidgetFunctions.memoryViewIds.take(model.memorySlots.size),
                memory.map { it.viewId },
            )
        }
        // and the union across models reaches every layout slot (Atlas: the third memory button)
        val union = WidgetFunctions.Model.entries
            .flatMap { WidgetFunctions.all(it) }.map { it.viewId }.toSet()
        assertEquals(9, union.size)
    }

    @Test
    fun `front defrost property id follows the model`() {
        assertEquals(269755136, WidgetFunctions.defrostFrontSpec(WidgetFunctions.Model.CITYRAY).propertyId)
        assertEquals(269753088, WidgetFunctions.defrostFrontSpec(WidgetFunctions.Model.PREFACE).propertyId)
        assertEquals(269027328, WidgetFunctions.defrostFrontSpec(WidgetFunctions.Model.ATLAS).propertyId)
    }

    @Test
    fun `seat memory address is the confirmed one - Button 2a equals reference 2e`() {
        val first = WidgetFunctions.byId(WidgetFunctions.Model.CITYRAY, "memory_1")
        assertNotNull(first)
        assertEquals(760219136, first!!.propertyId)
        assertEquals(1, first.areaId)
        assertEquals(FunctionKind.SLOT, first.kind)
    }

    @Test
    fun `byId answers every registry id and nothing else`() {
        val model = WidgetFunctions.Model.CITYRAY
        for (spec in WidgetFunctions.all(model)) {
            // byId rebuilds the list per call — compare identity keys, not instances
            assertEquals(spec.id, WidgetFunctions.byId(model, spec.id)?.id)
            assertEquals(spec.propertyId, WidgetFunctions.byId(model, spec.id)?.propertyId)
        }
        assertEquals(null, WidgetFunctions.byId(model, "nonexistent"))
    }

    @Test
    fun `tracked property ids cover every spec`() {
        val model = WidgetFunctions.Model.CITYRAY
        val tracked = WidgetFunctions.trackedPropertyIds(model)
        for (spec in WidgetFunctions.all(model)) {
            assertTrue("${spec.id} not tracked", tracked.contains(spec.propertyId))
        }
    }
}
