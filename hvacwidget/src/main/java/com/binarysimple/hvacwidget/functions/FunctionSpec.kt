package com.binarysimple.hvacwidget.functions

/**
 * Declarative description of one car function the widget shows and commands (S010): the id IS
 * the status key (snapshot key), propertyId/areaId say where it lives on the VHAL bus, and
 * [kind] picks the semantics — a leveled function (seat/wheel heat with encoded base|level
 * values), a plain toggle (defrost 0/1, recirculation with its ON/OFF codes), or a seat-memory
 * slot (restore: writing the slot's value makes the car move the seat to the saved position).
 *
 * [labelRes]/[iconRes]/[viewId] bind the spec to its button in the widget layout — the
 * renderer builds RemoteViews FROM the registry, so a NEW FUNCTION IS ONE REGISTRY ENTRY:
 * render, poll, taps and port never change. `0` means "not bound" (nothing renders it).
 *
 * [autoReadCode] is display-only: the CAR may answer a leveled read with its "auto" code even
 * without our involvement (the stock AUTO button) — the fold keeps it from rendering as
 * garbage. Auto ENTRANCES (writes to dedicated AUTO properties) do not exist here: they were
 * removed from the :app panel 2026-10-17 by the planner's decision (D-009) and never carried
 * into the widget.
 *
 * The logic members are pure (no Android imports) and JVM-tested.
 */
class FunctionSpec(
    /** Status key — the status snapshot is keyed by it. */
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
    /** [FunctionKind.SLOT] only: restore value(s) of this slot, model-dependent registry data. */
    val slots: IntArray? = null,
    val labelRes: Int = 0,
    val iconRes: Int = 0,
    /** Layout slot this function renders into; 0 = no button (never rendered). */
    val viewId: Int = 0,
) {

    /** Display ordinal (0..3) of a raw read; 0 for off, unknown, garbage — or a toggle/slot. */
    fun levelOf(raw: Int?): Int {
        if (kind != FunctionKind.LEVELS || raw == null) return 0
        val table = levels ?: return 0
        val normalized = when {
            raw <= 0 -> 0
            raw == autoReadCode -> table.last()
            table.contains(raw) -> raw
            raw in 1 until table.size -> table[raw]
            else -> 0
        }
        return table.indexOf(normalized).takeIf { it >= 0 } ?: 0
    }

    /** Whether the button reads as active (colored) for the raw property value. */
    fun isActive(raw: Int?): Boolean = when (kind) {
        FunctionKind.LEVELS -> levelOf(raw) > 0
        FunctionKind.TOGGLE -> raw != null && raw == onValue
        FunctionKind.SLOT -> slotIndexFromStatus(raw) >= 0
    }

    /** Value to write for the next tap on a leveled function, given the current display ordinal. */
    fun nextLevelValue(currentOrdinal: Int): Int {
        val table = levels ?: return offValue
        val index = if (currentOrdinal in 1..table.lastIndex) currentOrdinal else 0
        val next = if (index >= table.lastIndex) 0 else index + 1
        return table[next]
    }

    /** Value to write to switch a toggle on/off. */
    fun toggleValue(turnOn: Boolean): Int = if (turnOn) onValue else offValue

    /**
     * Index of the slot a raw read corresponds to ([slots] position), -1 when the read does
     * not match any known slot value — "no active slot", NEVER a guessed one (the slot
     * ladders are model-dependent and unconfirmed until a GU round, geely-references 2e).
     */
    fun slotIndexFromStatus(raw: Int?): Int {
        if (kind != FunctionKind.SLOT || raw == null) return -1
        return slots?.indexOf(raw) ?: -1
    }
}

/** The value semantics a car function carries. */
enum class FunctionKind { LEVELS, TOGGLE, SLOT }
