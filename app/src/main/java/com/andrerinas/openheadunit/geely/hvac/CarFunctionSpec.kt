package com.andrerinas.openheadunit.geely.hvac

/**
 * Declarative description of one car function the panel can show and command (F010): the id IS
 * the status key ([Hvac.statusKeys] entry), the property/area pair says where it lives on the
 * VHAL bus, and [kind] picks the semantics — a leveled function (seat/wheel heat with encoded
 * base|level values) or a plain toggle (defrost 0/1, recirculation with its ON/OFF codes).
 *
 * [autoReadCode] is display-only: the CAR may answer a leveled read with its "auto" code even
 * without our involvement (the stock AUTO button), and the fold keeps it from rendering as
 * garbage. The F008 auto ENTRANCE (writes to the dedicated AUTO properties) was removed
 * 2026-10-17 by the planner's decision — rounds 4–6 showed the car silently ignoring the
 * write for the driver zone (конспект §2.10).
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
    /** [FunctionKind.LEVELS] only: "auto" code the car may answer with instead of a level. */
    val autoReadCode: Int? = null,
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

    /** Value to write for the next tap on a leveled function, given the current display ordinal. */
    fun nextLevelValue(currentOrdinal: Int): Int {
        val table = levels ?: return offValue
        return table[Hvac.nextLevelOrdinal(currentOrdinal, table)]
    }

    /** Value to write to switch a toggle on/off. */
    fun toggleValue(turnOn: Boolean): Int = if (turnOn) onValue else offValue
}

/** The two value semantics a car function carries (a plain BINARY and a coded toggle fold into TOGGLE). */
enum class FunctionKind { LEVELS, TOGGLE }

/**
 * One entry of the connect-time probe list: its short log label plus the bus address, backed
 * by the tracked function it probes.
 */
class CarFunctionProbe(
    val spec: CarFunctionSpec,
    val label: String,
    val propertyId: Int,
    val areaId: Int,
) {
    constructor(spec: CarFunctionSpec, label: String) : this(spec, label, spec.propertyId, spec.areaId)
}
