package com.andrerinas.openheadunit.geely.hvac

/**
 * The registry of every car function the panel tracks — the single source of truth (F010).
 * The per-model lists are built once (ids do not depend on the model; only the model-gated
 * front defrost property id does), so lookups are safe from any thread.
 *
 * The F008 auto-heat functions (the dedicated AUTO properties and their companions) left the
 * registry on 2026-10-17 by the planner's decision — rounds 4–6 showed the car silently
 * ignoring the entrance write for the driver zone while the identical write for the passenger
 * confirmed (конспект §2.10). The car can still ANSWER the manual seat property with the auto
 * read code (the stock AUTO button) — that stays a display-only fold ([CarFunctionSpec.autoReadCode]).
 */
object GeelyCarFunctions {

    /** Seat zones in panel order: driver, passenger, rear-left, rear-right. */
    val SEAT_ZONES = intArrayOf(Hvac.ZONE_DRIVER, Hvac.ZONE_PASSENGER, Hvac.ZONE_REAR_LEFT, Hvac.ZONE_REAR_RIGHT)

    fun seatSpec(zone: Int): CarFunctionSpec = CarFunctionSpec(
        id = Hvac.seatKey(zone),
        propertyId = Hvac.PROP_SEAT_HEATING, areaId = zone,
        kind = FunctionKind.LEVELS, levels = Hvac.SEAT_HEAT_LEVELS,
        autoReadCode = Hvac.READ_AUTO_SEAT_HEAT,
    )

    val wheelSpec: CarFunctionSpec = CarFunctionSpec(
        id = Hvac.KEY_WHEEL,
        propertyId = Hvac.PROP_STEERING_WHEEL_HEAT, areaId = Hvac.GLOBAL_AREA,
        kind = FunctionKind.LEVELS, levels = Hvac.WHEEL_HEAT_LEVELS,
    )

    fun defrostFrontSpec(model: Hvac.CarModel): CarFunctionSpec = CarFunctionSpec(
        id = Hvac.KEY_DEFROST_FRONT,
        propertyId = model.defrostFrontPropertyId, areaId = Hvac.GLOBAL_AREA,
        kind = FunctionKind.TOGGLE, onValue = 1, offValue = 0,
    )

    val defrostRearSpec = CarFunctionSpec(
        id = Hvac.KEY_DEFROST_REAR,
        propertyId = Hvac.PROP_DEFROST_REAR, areaId = Hvac.GLOBAL_AREA,
        kind = FunctionKind.TOGGLE, onValue = 1, offValue = 0,
    )

    val defrostMaxSpec = CarFunctionSpec(
        id = Hvac.KEY_DEFROST_MAX,
        propertyId = Hvac.PROP_DEFROST_FRONT_MAX, areaId = Hvac.GLOBAL_AREA,
        kind = FunctionKind.TOGGLE, onValue = 1, offValue = 0,
    )

    val recirculationSpec = CarFunctionSpec(
        id = Hvac.KEY_RECIRC,
        propertyId = Hvac.PROP_CIRCULATION, areaId = Hvac.GLOBAL_AREA,
        kind = FunctionKind.TOGGLE, onValue = Hvac.CIRCULATION_ON, offValue = Hvac.CIRCULATION_OFF,
    )

    /** Manual seat specs in zone order. */
    private val seatSpecs: List<CarFunctionSpec> = SEAT_ZONES.map { seatSpec(it) }

    /** All tracked functions for the model, in [Hvac.statusKeys] order (seat block, wheel, rest). */
    private val byModel: Map<Hvac.CarModel, List<CarFunctionSpec>> =
        Hvac.CarModel.values().associateWith { model ->
            seatSpecs + listOf(
                wheelSpec,
                defrostFrontSpec(model), defrostRearSpec, defrostMaxSpec, recirculationSpec,
            )
        }

    fun all(model: Hvac.CarModel): List<CarFunctionSpec> = byModel.getValue(model)

    /** Spec by status key; ids are model-independent, so any model's list answers. */
    fun byId(id: String): CarFunctionSpec? = byIdIndex[id]

    private val byIdIndex: Map<String, CarFunctionSpec> =
        all(Hvac.CarModel.CITYRAY).associateBy { it.id }

    /** Every distinct property ID the panel may touch, plus all model front-defrost ids (catalog dump). */
    fun trackedPropertyIds(): Set<Int> =
        all(Hvac.CarModel.CITYRAY).map { it.propertyId }.toSet() +
            Hvac.CarModel.values().map { it.defrostFrontPropertyId }

    /**
     * The connect-time probe list (isFunctionSupported / catalog): every (property, area) the
     * panel reads or writes, the front defrost of EVERY model included, with the historical
     * log labels — EcarxCarPort's probe lines come out exactly as before F010.
     */
    fun probes(): List<CarFunctionProbe> = buildList {
        for (seat in seatSpecs) {
            add(CarFunctionProbe(seat, "seat"))
        }
        add(CarFunctionProbe(wheelSpec, "wheel"))
        for (model in Hvac.CarModel.values()) {
            add(CarFunctionProbe(defrostFrontSpec(model), "defrost_front@${model.name}"))
        }
        add(CarFunctionProbe(defrostRearSpec, "defrost_rear"))
        add(CarFunctionProbe(defrostMaxSpec, "defrost_max"))
        add(CarFunctionProbe(recirculationSpec, "recirc"))
    }
}
