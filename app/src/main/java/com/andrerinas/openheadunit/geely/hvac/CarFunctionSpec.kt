package com.andrerinas.openheadunit.geely.hvac

/**
 * Declarative description of one car function the panel can show and command (F010): the id IS
 * the status key ([Hvac.statusKeys] entry), the property/area pair says where it lives on the
 * VHAL bus, and [kind] picks the semantics — a leveled function (seat/wheel heat with encoded
 * base|level values and an optional linked AUTO property, F008) or a plain toggle (defrost 0/1,
 * recirculation with its ON/OFF codes).
 *
 * The registry [GeelyCarFunctions] built from these specs is the SINGLE source of truth for
 * every tracked property: status reads, both ports' connect-time probes, and the panel's
 * button tree all derive from it — before F010 the same list lived in three places that had
 * to be edited in lockstep.
 *
 * Pure Kotlin, no Android imports: unit-testable on the JVM (GeelyCarFunctionsTest).
 */
class CarFunctionSpec(
    /** Status key — the panel snapshot is keyed by it (log format of HVAC_Log). */
    val id: String,
    val propertyId: Int,
    val areaId: Int,
    val kind: FunctionKind,
    /** [FunctionKind.LEVELS] only: encoded level values, index = display ordinal (0 = off). */
    val levels: IntArray? = null,
    /** [FunctionKind.TOGGLE] only: raw read equal to this means on. */
    val onValue: Int = 1,
    /** [FunctionKind.TOGGLE] only: value written to switch off. */
    val offValue: Int = 0,
    /** [FunctionKind.LEVELS] only: "auto" code a manual property may answer with instead of a level. */
    val autoReadCode: Int? = null,
    /** The linked AUTO function (F008), when this leveled function has one. */
    val auto: CarFunctionSpec? = null,
    /**
     * The AUTO property's duration knob (S008): meaning only on an AUTO spec — the TIME
     * property the catalog and the voice protocol expose for it, and the "on" value to write
     * there (TIME_2 until the duration semantics are confirmed on the car).
     */
    val autoTimePropertyId: Int? = null,
    val autoTimeOnValue: Int? = null,
) {

    /** Display ordinal (0..3) of a raw read; 0 for off, unknown, garbage — or a toggle. */
    fun levelOf(raw: Int?): Int {
        if (kind != FunctionKind.LEVELS || raw == null) return 0
        val table = levels ?: return 0
        val normalized = Hvac.normalizeLevel(raw, table, autoReadCode)
        return table.indexOf(normalized).takeIf { it >= 0 } ?: 0
    }

    /** Whether the button reads as on (colored icon) for the raw property value. */
    fun isActive(raw: Int?): Boolean = when (kind) {
        FunctionKind.LEVELS -> levelOf(raw) > 0
        FunctionKind.TOGGLE -> raw != null && raw == onValue
    }

    /** What the button shows: while the linked auto runs, its level (F008 display rule). */
    fun displayLevel(manualRaw: Int?, autoRaw: Int?): Int =
        Hvac.displayLevel(levelOf(manualRaw), auto?.levelOf(autoRaw) ?: 0)

    /**
     * Does this pair of raw reads mean the car answers the zone IN auto (the S008 verdict
     * rule)? Round 4: a passenger auto-code write the car actually accepted showed up as the
     * auto read code on BOTH properties within one poll, while the twice-silently-ignored
     * driver write never moved either — so an auto answer on either raw is the car's yes,
     * and so is a known auto level on the AUTO property (the entrance we would have written).
     * A mirrored manual level (round 4: manual L1 mirrored onto the auto property as
     * base|1) matches neither clause — never reads as auto.
     */
    fun readsAuto(manualRaw: Int?, autoRaw: Int?): Boolean {
        if (autoReadCode != null && (manualRaw == autoReadCode || autoRaw == autoReadCode)) return true
        val table = auto?.levels ?: return false
        return autoRaw != null && table.indexOf(autoRaw) > 0
    }

    /**
     * Positive evidence the zone LEFT auto: the linked AUTO property answers off again.
     * Only the AUTO property counts here — after an exit the manual property may keep
     * answering the auto code for a while (round 4: seat_auto went 0 while seat stayed on
     * the code), and a mirrored auto code on the AUTO property means still on, not off.
     */
    fun readsAutoOff(autoRaw: Int?): Boolean {
        val table = auto?.levels ?: return autoRaw == 0
        return autoRaw != null && table.indexOf(autoRaw) == 0
    }

    /** Value to write for the next tap on a leveled function, given the current display ordinal. */
    fun nextLevelValue(currentOrdinal: Int): Int {
        val table = levels ?: return offValue
        return table[Hvac.nextLevelOrdinal(currentOrdinal, table)]
    }

    /** Value to write to switch a toggle on/off. */
    fun toggleValue(turnOn: Boolean): Int = if (turnOn) onValue else offValue

    /**
     * Ordered candidate writes to ENTER auto at display [level] (1..3); the winner is the
     * first rung the CAR confirms ([readsAuto]) — round 4 showed a write the port accepts
     * can still be silently ignored, so "returned ok" is not a verdict (S008). The ГУ round
     * of 2026-10-12 rejected the base|level write on the dedicated AUTO property, so the
     * ladder leads with the entrances the references actually point at: the manual
     * property's own auto level code (catalog: "уровни/авто" — the same code an auto-enabled
     * seat answers with), then the AUTO property's TIME knob (the only auto surface the
     * voice protocol exposes), and only then the AUTO property itself in both value
     * encodings. A function without auto has an empty ladder.
     */
    fun autoStartAttempts(level: Int): List<CarWriteAttempt> {
        val linked = auto ?: return emptyList()
        return buildList {
            autoReadCode?.let { add(CarWriteAttempt("auto-code", propertyId, areaId, it)) }
            linked.autoTimePropertyId?.let { timeProperty ->
                val timeValue = linked.autoTimeOnValue ?: 0
                add(CarWriteAttempt("time", timeProperty, linked.areaId, timeValue))
                if (linked.areaId != Hvac.GLOBAL_AREA) {
                    add(CarWriteAttempt("time-global", timeProperty, Hvac.GLOBAL_AREA, timeValue))
                }
            }
            add(CarWriteAttempt("auto-plain", linked.propertyId, linked.areaId, level))
            add(CarWriteAttempt("auto-encoded", linked.propertyId, linked.areaId, linked.levels?.getOrNull(level) ?: level))
        }
    }

    /** Ordered writes to LEAVE auto: every entrance of [autoStartAttempts] back to zero, best-effort. */
    fun autoOffAttempts(): List<CarWriteAttempt> {
        val linked = auto ?: return emptyList()
        return buildList {
            linked.autoTimePropertyId?.let { timeProperty ->
                add(CarWriteAttempt("time-off", timeProperty, linked.areaId, 0))
                if (linked.areaId != Hvac.GLOBAL_AREA) {
                    add(CarWriteAttempt("time-off-global", timeProperty, Hvac.GLOBAL_AREA, 0))
                }
            }
            add(CarWriteAttempt("auto-off", linked.propertyId, linked.areaId, 0))
            add(CarWriteAttempt("manual-off", propertyId, areaId, 0))
        }
    }
}

/** One candidate write of the auto start/off ladder: a log label plus its bus destination. */
data class CarWriteAttempt(val label: String, val propertyId: Int, val areaId: Int, val value: Int)

/** The two value semantics a car function carries (a plain BINARY and a coded toggle fold into TOGGLE). */
enum class FunctionKind { LEVELS, TOGGLE }

/**
 * One entry of the connect-time probe list: its short log label plus the bus address. Probes
 * of tracked functions carry the spec too; companion properties (auto TIME/SWITCH) are
 * probed bare — they are diagnostics, not buttons.
 */
class CarFunctionProbe(
    val spec: CarFunctionSpec?,
    val label: String,
    val propertyId: Int,
    val areaId: Int,
) {
    constructor(spec: CarFunctionSpec, label: String) : this(spec, label, spec.propertyId, spec.areaId)

    companion object {
        /** A probe of a companion property the panel never reads or buttons (S008 diagnostics). */
        fun bare(label: String, propertyId: Int, areaId: Int) =
            CarFunctionProbe(null, label, propertyId, areaId)
    }
}

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

    val wheelSpec: CarFunctionSpec = CarFunctionSpec(
        id = Hvac.KEY_WHEEL,
        propertyId = Hvac.PROP_STEERING_WHEEL_HEAT, areaId = Hvac.GLOBAL_AREA,
        kind = FunctionKind.LEVELS, levels = Hvac.WHEEL_HEAT_LEVELS,
        auto = CarFunctionSpec(
            id = Hvac.KEY_WHEEL_AUTO,
            propertyId = Hvac.PROP_AUTO_STEERING_WHEEL_HEAT, areaId = Hvac.GLOBAL_AREA,
            kind = FunctionKind.LEVELS, levels = Hvac.AUTO_WHEEL_HEAT_LEVELS,
            autoTimePropertyId = Hvac.PROP_AUTO_STEERING_WHEEL_HEAT_TIME,
            autoTimeOnValue = Hvac.AUTO_WHEEL_HEAT_TIME_LEVELS[2],
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
            setOf(
                Hvac.PROP_AUTO_SEAT_HEATING_TIME,
                Hvac.PROP_AUTO_STEERING_WHEEL_HEAT_TIME,
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
        add(CarFunctionProbe(wheelSpec.auto!!, "wheel_auto"))
        for (seat in seatSpecs) {
            add(CarFunctionProbe.bare("seat_auto_time", Hvac.PROP_AUTO_SEAT_HEATING_TIME, seat.areaId))
        }
        add(CarFunctionProbe.bare("wheel_auto_time", Hvac.PROP_AUTO_STEERING_WHEEL_HEAT_TIME, Hvac.GLOBAL_AREA))
        add(CarFunctionProbe.bare("wheel_auto_switch", Hvac.PROP_AUTO_STEERING_WHEEL_HEAT_SWITCH, Hvac.GLOBAL_AREA))
        for (model in Hvac.CarModel.values()) {
            add(CarFunctionProbe(defrostFrontSpec(model), "defrost_front@${model.name}"))
        }
        add(CarFunctionProbe(defrostRearSpec, "defrost_rear"))
        add(CarFunctionProbe(defrostMaxSpec, "defrost_max"))
        add(CarFunctionProbe(recirculationSpec, "recirc"))
    }
}
