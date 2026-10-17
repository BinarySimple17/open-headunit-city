package com.andrerinas.openheadunit.geely.hvac

/**
 * The registry of every car function the panel tracks — the single source of truth (F010).
 * The per-model lists are built once (ids do not depend on the model; only the model-gated
 * front defrost property id does), so lookups are safe from any thread.
 */
object GeelyCarFunctions {

    /** Seat zones in panel order: driver, passenger, rear-left, rear-right. */
    val SEAT_ZONES = intArrayOf(Hvac.ZONE_DRIVER, Hvac.ZONE_PASSENGER, Hvac.ZONE_REAR_LEFT, Hvac.ZONE_REAR_RIGHT)

    fun seatSpec(zone: Int): CarFunctionSpec = CarFunctionSpec(
        id = Hvac.seatKey(zone),
        propertyId = Hvac.PROP_SEAT_HEATING, areaId = zone,
        kind = FunctionKind.LEVELS, levels = Hvac.SEAT_HEAT_LEVELS,
        autoReadCode = Hvac.READ_AUTO_SEAT_HEAT,
        auto = CarFunctionSpec(
            id = Hvac.seatAutoKey(zone),
            propertyId = Hvac.PROP_AUTO_SEAT_HEATING, areaId = zone,
            kind = FunctionKind.LEVELS, levels = Hvac.AUTO_SEAT_HEAT_LEVELS,
            autoTimePropertyId = Hvac.PROP_AUTO_SEAT_HEATING_TIME,
            autoTimeOnValue = Hvac.AUTO_SEAT_HEAT_TIME_LEVELS[2],
        ),
    )

    /**
     * The wheel's auto is the SWITCH property (round 5, 2026-10-17: the stock AUTO button
     * writes AUTO_STEERING_WHEEL_HEAT_SWITCH 1/0 and PA_SWH_AutoReqSts follows On/Off) —
     * the dedicated AUTO property (269025792) this link used before is notavailable on this
     * car and stays behind only as a bare diagnostics probe. The status key stays
     * "wheel_auto"; it now reads the switch (0/1).
     */
    val wheelSpec: CarFunctionSpec = CarFunctionSpec(
        id = Hvac.KEY_WHEEL,
        propertyId = Hvac.PROP_STEERING_WHEEL_HEAT, areaId = Hvac.GLOBAL_AREA,
        kind = FunctionKind.LEVELS, levels = Hvac.WHEEL_HEAT_LEVELS,
        auto = CarFunctionSpec(
            id = Hvac.KEY_WHEEL_AUTO,
            propertyId = Hvac.PROP_AUTO_STEERING_WHEEL_HEAT_SWITCH, areaId = Hvac.GLOBAL_AREA,
            kind = FunctionKind.TOGGLE, onValue = 1, offValue = 0,
        ),
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

    /** Manual seat specs in zone order; each carries its linked AUTO spec (shared across models). */
    private val seatSpecs: List<CarFunctionSpec> = SEAT_ZONES.map { seatSpec(it) }

    /** All tracked functions for the model, in [Hvac.statusKeys] order (seat block, auto block, rest). */
    private val byModel: Map<Hvac.CarModel, List<CarFunctionSpec>> =
        Hvac.CarModel.values().associateWith { model ->
            seatSpecs + seatSpecs.map { it.auto!! } +
                listOf(
                    wheelSpec, wheelSpec.auto!!,
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
            Hvac.CarModel.values().map { it.defrostFrontPropertyId } +
            // The auto companions: not polled, but the android.car catalog dump and the
            // adaptapi probes should tell us whether THIS car registers them at all (S008).
            // The legacy wheel AUTO property stays listed even though the spec link moved to
            // the SWITCH (round 5) — the catalog keeps telling us it is notavailable.
            setOf(
                Hvac.PROP_AUTO_SEAT_HEATING_TIME,
                Hvac.PROP_AUTO_STEERING_WHEEL_HEAT_TIME,
                Hvac.PROP_AUTO_STEERING_WHEEL_HEAT,
                Hvac.PROP_AUTO_STEERING_WHEEL_HEAT_SWITCH,
            )

    /**
     * The connect-time probe list (isFunctionSupported / catalog): every (property, area) the
     * panel reads or writes, the front defrost of EVERY model included, with the historical
     * log labels — EcarxCarPort's probe lines come out exactly as before F010. The auto
     * companions follow their auto properties (bare probes: diagnostics, not buttons).
     */
    fun probes(): List<CarFunctionProbe> = buildList {
        for (seat in seatSpecs) {
            add(CarFunctionProbe(seat, "seat"))
            add(CarFunctionProbe(seat.auto!!, "seat_auto"))
        }
        add(CarFunctionProbe(wheelSpec, "wheel"))
        // The wheel auto link probes under its round-5 name: the SWITCH is the control now.
        add(CarFunctionProbe(wheelSpec.auto!!, "wheel_auto_switch"))
        // The dedicated AUTO property the spec used before: notavailable on this car, kept
        // as a diagnostics probe so the catalog keeps telling us so.
        add(CarFunctionProbe.bare("wheel_auto_legacy", Hvac.PROP_AUTO_STEERING_WHEEL_HEAT, Hvac.GLOBAL_AREA))
        for (seat in seatSpecs) {
            add(CarFunctionProbe.bare("seat_auto_time", Hvac.PROP_AUTO_SEAT_HEATING_TIME, seat.areaId))
        }
        add(CarFunctionProbe.bare("wheel_auto_time", Hvac.PROP_AUTO_STEERING_WHEEL_HEAT_TIME, Hvac.GLOBAL_AREA))
        for (model in Hvac.CarModel.values()) {
            add(CarFunctionProbe(defrostFrontSpec(model), "defrost_front@${model.name}"))
        }
        add(CarFunctionProbe(defrostRearSpec, "defrost_rear"))
        add(CarFunctionProbe(defrostMaxSpec, "defrost_max"))
        add(CarFunctionProbe(recirculationSpec, "recirc"))
    }
}
