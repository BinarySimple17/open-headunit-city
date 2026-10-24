package com.binarysimple.hvacwidget.functions

import com.binarysimple.hvacwidget.R

/**
 * The registry of every car function the widget tracks — the single source of truth (S010).
 * The per-model lists are built from the [Model] the settings carry; render, poll, taps and
 * the port all derive from here, so ADDING A FUNCTION IS ONE ENTRY in [all] plus an icon and
 * a layout slot.
 *
 * Property IDs are the Geely/ECARX firmware vocabulary (docs/geely-references.md 2.3, triple-
 * cross-checked and GU-confirmed): seats 268763648 zones 1/4/16/64 with base|level codes,
 * wheel 269025536, rear defrost 268698368, recirculation with its ON/OFF codes; the front
 * defrost id is MODEL-SPECIFIC. Seat memory: SEAT_MEMORY 760219136 (0x2D500500, Button 2a =
 * SeatMemoryWidget 2e), driver area 1; the slot VALUES are model-dependent — see [Model].
 */
object WidgetFunctions {

    /** The platform's "global zone" sentinel (the adaptapi convention: Integer.MIN_VALUE). */
    const val GLOBAL_AREA: Int = Int.MIN_VALUE

    const val PROP_SEAT_HEATING = 268763648
    const val PROP_STEERING_WHEEL_HEAT = 269025536
    const val PROP_DEFROST_REAR = 268698368
    const val PROP_CIRCULATION = 268632320
    const val CIRCULATION_ON = 268632321
    const val CIRCULATION_OFF = 268632322

    /** Seat memory restore property (geely-references 2a/2e) and its driver-side area. */
    const val PROP_SEAT_MEMORY = 760219136
    const val SEAT_MEMORY_AREA = 1

    /** "auto" code the car may answer the manual seat property with (the stock AUTO button). */
    const val READ_AUTO_SEAT_HEAT = 0x1005020F

    val WHEEL_HEAT_LEVELS = intArrayOf(0, 269025537, 269025538, 269025539)
    val SEAT_HEAT_LEVELS = intArrayOf(0, 268763649, 268763650, 268763651)

    /**
     * Head-unit models: the model-gated front-defrost property id and the seat-memory slot
     * ladder (restore values, base|N of SEAT_MEMORY — geely-references 2e). The ladders are
     * MODEL-DEPENDENT DATA, not logic, and stay CANDIDATES until the GU round: the reference
     * was built under GEELY ATLAS (three slots base+4/+3/+2); the planner's CITYRAY has TWO
     * positions — the values confirm the read-back probe (Task 4). PREFACE has neither a
     * confirmed ladder nor a probe yet: empty is honest, not lazy.
     */
    enum class Model(val defrostFrontPropertyId: Int, val memorySlots: List<Int>) {
        CITYRAY(269755136, listOf(760219140, 760219139)),
        PREFACE(269753088, emptyList()),
        ATLAS(269027328, listOf(760219140, 760219139, 760219138)),
    }

    /** Layout slots of the memory buttons, in slot order (renderer hides the surplus ones). */
    val memoryViewIds = listOf(R.id.btn_memory_1, R.id.btn_memory_2, R.id.btn_memory_3)

    val wheelSpec = FunctionSpec(
        id = "wheel",
        propertyId = PROP_STEERING_WHEEL_HEAT, areaId = GLOBAL_AREA,
        kind = FunctionKind.LEVELS, levels = WHEEL_HEAT_LEVELS,
        labelRes = R.string.btn_wheel, viewId = R.id.btn_wheel,
    )

    fun seatSpec(id: String, zone: Int, labelRes: Int, viewId: Int) = FunctionSpec(
        id = id,
        propertyId = PROP_SEAT_HEATING, areaId = zone,
        kind = FunctionKind.LEVELS, levels = SEAT_HEAT_LEVELS,
        autoReadCode = READ_AUTO_SEAT_HEAT,
        labelRes = labelRes, viewId = viewId,
    )

    fun defrostFrontSpec(model: Model) = FunctionSpec(
        id = "defrost_front",
        propertyId = model.defrostFrontPropertyId, areaId = GLOBAL_AREA,
        kind = FunctionKind.TOGGLE, onValue = 1, offValue = 0,
        labelRes = R.string.btn_defrost_front, viewId = R.id.btn_defrost_front,
    )

    val defrostRearSpec = FunctionSpec(
        id = "defrost_rear",
        propertyId = PROP_DEFROST_REAR, areaId = GLOBAL_AREA,
        kind = FunctionKind.TOGGLE, onValue = 1, offValue = 0,
        labelRes = R.string.btn_defrost_rear, viewId = R.id.btn_defrost_rear,
    )

    val recircSpec = FunctionSpec(
        id = "recirc",
        propertyId = PROP_CIRCULATION, areaId = GLOBAL_AREA,
        kind = FunctionKind.TOGGLE, onValue = CIRCULATION_ON, offValue = CIRCULATION_OFF,
        labelRes = R.string.btn_recirc, viewId = R.id.btn_recirc,
    )

    /**
     * Memory slot specs from the model's ladder — one SLOT spec per saved position, values
     * straight from [Model.memorySlots] (registry data, never logic). Saving positions is the
     * CAR's own interface — the widget restores only (planner's decision, 2026-10-23).
     */
    private fun memorySpecs(model: Model): List<FunctionSpec> = model.memorySlots.mapIndexed { index, value ->
        FunctionSpec(
            id = "memory_${index + 1}",
            propertyId = PROP_SEAT_MEMORY, areaId = SEAT_MEMORY_AREA,
            kind = FunctionKind.SLOT, slots = intArrayOf(value),
            labelRes = memoryLabelRes(index), viewId = memoryViewIds[index],
        )
    }

    private fun memoryLabelRes(index: Int): Int = when (index) {
        0 -> R.string.btn_memory_1
        1 -> R.string.btn_memory_2
        else -> R.string.btn_memory_3
    }

    /** All tracked functions for the model, in button order (climate block, then memory). */
    fun all(model: Model): List<FunctionSpec> = listOf(
        wheelSpec,
        seatSpec("seat_driver", ZONE_DRIVER, R.string.btn_seat_driver, R.id.btn_seat_driver),
        seatSpec("seat_passenger", ZONE_PASSENGER, R.string.btn_seat_passenger, R.id.btn_seat_passenger),
        defrostFrontSpec(model), defrostRearSpec, recircSpec,
    ) + memorySpecs(model)

    /** Spec by status key for the model (memory ids are model-dependent); null = unknown tap. */
    fun byId(model: Model, id: String): FunctionSpec? = all(model).firstOrNull { it.id == id }

    /** Every distinct property id the widget may touch for the model (catalog dump). */
    fun trackedPropertyIds(model: Model): Set<Int> = all(model).map { it.propertyId }.toSet()

    /** Seat zones of the firmware's VehicleSeat bitmask family. */
    const val ZONE_DRIVER = 1
    const val ZONE_PASSENGER = 4
    const val ZONE_REAR_LEFT = 16
    const val ZONE_REAR_RIGHT = 64
}
