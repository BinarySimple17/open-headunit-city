package com.binarysimple.hvacwidget.seat

import com.binarysimple.hvacwidget.car.CarFunctionPort
import com.binarysimple.hvacwidget.functions.FunctionKind
import com.binarysimple.hvacwidget.functions.FunctionSpec
import com.binarysimple.hvacwidget.functions.WidgetFunctions

/**
 * Restore-only seat memory (S010; planner's decision 2026-10-23 — SAVING positions is the
 * car's own stock interface, the widget only recalls). Protocol per the SeatMemoryWidget
 * reference (geely-references 2e): SEAT_MEMORY 760219136 (0x2D500500), driver area 1, restore
 * values base|N — but the LADDER IS MODEL-DEPENDENT REGISTRY DATA
 * ([WidgetFunctions.Model.memorySlots]): the reference was built under GEELY ATLAS (three
 * slots), the planner's CITYRAY has two, and its exact values stay candidates until the GU
 * read-back probe confirms them. Nothing here hardcodes a slot count or a value.
 */
object SeatMemory {

    /** The value a restore tap writes for this SLOT spec; null for non-slot specs. */
    fun restoreValue(spec: FunctionSpec): Int? {
        if (spec.kind != FunctionKind.SLOT) return null
        return spec.slots?.firstOrNull()
    }

    /** Writes the restore value through the port; false when there was nothing to write. */
    fun restore(port: CarFunctionPort, spec: FunctionSpec): Boolean {
        val value = restoreValue(spec) ?: return false
        return port.setInt(spec.propertyId, spec.areaId, value)
    }

    /**
     * Which model slot a raw read corresponds to (index into [WidgetFunctions.Model.memorySlots]),
     * -1 = "no active slot" — the read either failed or named a value outside the model's
     * ladder, and a foreign value never paints as a slot.
     */
    fun activeSlotIndex(model: WidgetFunctions.Model, raw: Int?): Int {
        if (raw == null) return -1
        return model.memorySlots.indexOf(raw)
    }
}
