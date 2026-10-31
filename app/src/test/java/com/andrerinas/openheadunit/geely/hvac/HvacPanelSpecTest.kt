package com.andrerinas.openheadunit.geely.hvac

import com.andrerinas.openheadunit.geely.panel.ActionNode
import com.andrerinas.openheadunit.geely.panel.GroupDisplay
import com.andrerinas.openheadunit.geely.panel.GroupNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel's declarative tree (F010): it must reproduce the historical six-button column
 * exactly — this is the behavior freeze of the panel's look, expressed as a shape pin, so
 * the future "anchor -> categories -> windows" trees cannot silently change today's panel.
 */
class HvacPanelSpecTest {

    @Test
    fun `root leads with the F015 position group then the six historical buttons`() {
        val root = HvacPanelSpec.root(Hvac.CarModel.CITYRAY)
        assertEquals(
            listOf(
                "seat_position", "seat", "wheel", "defrost_front", "defrost_rear",
                "defrost_max", "recirc"
            ),
            root.map { it.id }
        )
        // PREFACE has no confirmed slot ladder — the group is honestly absent.
        assertEquals(
            listOf("seat", "wheel", "defrost_front", "defrost_rear", "defrost_max", "recirc"),
            HvacPanelSpec.root(Hvac.CarModel.PREFACE).map { it.id }
        )
    }

    @Test
    fun `seat position is an inline-row group of two self-colored slot buttons`() {
        val group = HvacPanelSpec.root(Hvac.CarModel.CITYRAY).first() as GroupNode<CarFunctionSpec>
        assertEquals(HvacPanelSpec.ID_SEAT_POSITION, group.id)
        assertEquals(GroupDisplay.INLINE_ROW, group.display)
        assertEquals(listOf("memory:1", "memory:2"), group.children.map { it.id })
        for ((index, child) in group.children.withIndex()) {
            // The planner's face: the same seat icon, the number as a badge over it.
            assertEquals(com.andrerinas.openheadunit.R.drawable.hvac_ic_seat_memory, child.iconRes)
            assertEquals((index + 1).toString(), child.badge)
            assertNotNull(child.activeIconRes)
            // The slot values are the model's ladder data (Cityray: the 2.4.3 reference).
            assertEquals(Hvac.CarModel.CITYRAY.memorySlots[index], child.function.slotValue)
        }
        assertNotNull(group.activeIconRes)
    }

    @Test
    fun `seats is an inline-row group over the four zones in panel order`() {
        val seats = HvacPanelSpec.root(Hvac.CarModel.PREFACE).first() as GroupNode<CarFunctionSpec>
        assertEquals(GroupDisplay.INLINE_ROW, seats.display)
        assertEquals(
            listOf("seat:1", "seat:4", "seat:16", "seat:64"),
            seats.children.map { it.id }
        )
        for (child in seats.children) {
            assertTrue(child is ActionNode<CarFunctionSpec>)
            assertNotNull(GeelyCarFunctions.byId(child.id))
        }
    }

    @Test
    fun `every action node binds a tracked function with matching id`() {
        fun walk(nodes: List<com.andrerinas.openheadunit.geely.panel.PanelNode<CarFunctionSpec>>) {
            for (node in nodes) {
                if (node is GroupNode<CarFunctionSpec>) walk(node.children)
                if (node is ActionNode<CarFunctionSpec>) {
                    val spec = GeelyCarFunctions.byId(node.id)
                    assertNotNull(node.id, spec)
                    assertEquals(node.id, spec!!.id)
                }
            }
        }
        walk(HvacPanelSpec.root(Hvac.CarModel.ATLAS))
    }

    @Test
    fun `front defrost node follows the model`() {
        val node = HvacPanelSpec.root(Hvac.CarModel.ATLAS)
            .first { it.id == Hvac.KEY_DEFROST_FRONT } as ActionNode<CarFunctionSpec>
        assertEquals(Hvac.CarModel.ATLAS.defrostFrontPropertyId, node.function.propertyId)
    }
}
