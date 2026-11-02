package com.andrerinas.openheadunit.geely.hvac

import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.geely.panel.ActionNode
import com.andrerinas.openheadunit.geely.panel.GroupDisplay
import com.andrerinas.openheadunit.geely.panel.GroupNode
import com.andrerinas.openheadunit.geely.panel.PanelNode

/**
 * The panel's button tree (F010) — the declarative description the shell renders. Today it
 * reproduces the historical column exactly: Seats (a group that
 * opens the four seat zones), Wheel, Front/Rear/MAX defrost, Recirculation. Tomorrow's
 * "anchor opens a list of categories, categories hold buttons or windows" is a different
 * tree over the same nodes — no renderer/navigation/command change needed.
 *
 * Every ActionNode's function comes from [GeelyCarFunctions] — the tree can only reference
 * tracked functions (HvacPanelSpecTest pins the shape). The model matters only for the
 * front-defrost property id, so the tree is built once per panel attach with the configured
 * [Hvac.CarModel].
 */
object HvacPanelSpec {

    /** The seat group's node id; doubles as the inline-row key and the "panel: seat row" log label. */
    const val ID_SEATS = "seat"

    /** The seat-position group's node id (F015) — first button of the anchor's column. */
    const val ID_SEAT_POSITION = "seat_position"

    /** Seat zone labels in panel order: driver, passenger, rear-left, rear-right. */
    private val seatLabelRes = listOf(
        R.string.hvac_seat_driver,
        R.string.hvac_seat_passenger,
        R.string.hvac_seat_rear_left,
        R.string.hvac_seat_rear_right,
    )

    /**
     * The root children, top to bottom — the F015 seat-position group first (the planner's
     * order: the position button leads the anchor's column), then the six historical buttons
     * in their historical order. The position group exists only when the model has a
     * confirmed slot ladder (PREFACE has none yet — the tree is honest about that).
     */
    fun root(model: Hvac.CarModel): List<PanelNode<CarFunctionSpec>> = buildList {
        if (model.memorySlots.isNotEmpty()) add(seatPosition(model))
        addAll(
            listOf(
                GroupNode(
                    id = ID_SEATS,
                    iconRes = R.drawable.hvac_ic_seat,
                    labelRes = R.string.hvac_btn_seats,
                    children = seatZones(),
                    display = GroupDisplay.INLINE_ROW,
                ),
                ActionNode(Hvac.KEY_WHEEL, R.drawable.hvac_ic_steer, R.string.hvac_btn_wheel, GeelyCarFunctions.wheelSpec),
                ActionNode(
                    Hvac.KEY_DEFROST_FRONT, R.drawable.hvac_ic_windshield, R.string.hvac_btn_front_defrost,
                    GeelyCarFunctions.defrostFrontSpec(model)
                ),
                ActionNode(Hvac.KEY_DEFROST_REAR, R.drawable.hvac_ic_rear_window, R.string.hvac_btn_rear_defrost, GeelyCarFunctions.defrostRearSpec),
                ActionNode(Hvac.KEY_DEFROST_MAX, R.drawable.hvac_ic_antifrost, R.string.hvac_btn_max_defrost, GeelyCarFunctions.defrostMaxSpec),
                ActionNode(Hvac.KEY_RECIRC, R.drawable.hvac_ic_recircule, R.string.hvac_btn_recirculation, GeelyCarFunctions.recirculationSpec),
            )
        )
    }

    /**
     * The seat-position group (F015): a tap opens the TWO position buttons beside the column
     * (the seats-group semantics), each a seat icon with its number badge over it; the active
     * position swaps to the green active icon by the car's read. The slot values are the
     * model's ladder data (root() only calls this when the model has one).
     */
    fun seatPosition(model: Hvac.CarModel): GroupNode<CarFunctionSpec> {
        val specs = GeelyCarFunctions.memorySlotSpecs(model)
        return GroupNode(
            id = ID_SEAT_POSITION,
            iconRes = R.drawable.hvac_ic_seat_memory,
            labelRes = R.string.hvac_btn_seat_position,
            children = listOf(
                ActionNode(
                    id = Hvac.memoryKey(1),
                    iconRes = R.drawable.hvac_ic_seat_memory,
                    labelRes = R.string.hvac_seat_position_1,
                    function = specs[0],
                    badge = "1",
                    activeIconRes = R.drawable.hvac_ic_seat_memory_act,
                ),
                ActionNode(
                    id = Hvac.memoryKey(2),
                    iconRes = R.drawable.hvac_ic_seat_memory,
                    labelRes = R.string.hvac_seat_position_2,
                    function = specs[1],
                    badge = "2",
                    activeIconRes = R.drawable.hvac_ic_seat_memory_act,
                ),
            ),
            display = GroupDisplay.INLINE_ROW,
            activeIconRes = R.drawable.hvac_ic_seat_memory_act,
        )
    }

    /** The four seat zone buttons in panel order, each bound to its zone's function spec. */
    fun seatZones(): List<ActionNode<CarFunctionSpec>> =
        GeelyCarFunctions.SEAT_ZONES.indices.map { index ->
            val zone = GeelyCarFunctions.SEAT_ZONES[index]
            ActionNode(
                id = Hvac.seatKey(zone),
                iconRes = R.drawable.hvac_ic_seat,
                labelRes = seatLabelRes[index],
                function = GeelyCarFunctions.seatSpec(zone),
            )
        }
}
