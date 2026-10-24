package com.binarysimple.hvacwidget.render

import com.binarysimple.hvacwidget.R
import com.binarysimple.hvacwidget.functions.FunctionKind
import com.binarysimple.hvacwidget.functions.FunctionSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/** Button paint per display state (S010 Task 2): states switch whole background drawables. */
class WidgetThemeTest {

    private val wheel = FunctionSpec(
        id = "wheel", propertyId = 269025536, areaId = Int.MIN_VALUE,
        kind = FunctionKind.LEVELS, levels = intArrayOf(0, 269025537, 269025538, 269025539),
    )
    private val defrost = FunctionSpec(
        id = "defrost_rear", propertyId = 268698368, areaId = Int.MIN_VALUE,
        kind = FunctionKind.TOGGLE, onValue = 1, offValue = 0,
    )
    private val memory = FunctionSpec(
        id = "memory_1", propertyId = 760219136, areaId = 1,
        kind = FunctionKind.SLOT, slots = intArrayOf(760219140),
    )

    @Test
    fun `levels paint gray then L1-L2-L3`() {
        assertEquals(R.drawable.widget_button_bg, WidgetTheme.backgroundRes(wheel, null))
        assertEquals(R.drawable.widget_button_bg, WidgetTheme.backgroundRes(wheel, 0))
        assertEquals(R.drawable.widget_button_l1, WidgetTheme.backgroundRes(wheel, 269025537))
        assertEquals(R.drawable.widget_button_l2, WidgetTheme.backgroundRes(wheel, 269025538))
        assertEquals(R.drawable.widget_button_l3, WidgetTheme.backgroundRes(wheel, 269025539))
    }

    @Test
    fun `toggle paints on-shape only on its on-value`() {
        assertEquals(R.drawable.widget_button_l1, WidgetTheme.backgroundRes(defrost, 1))
        assertEquals(R.drawable.widget_button_bg, WidgetTheme.backgroundRes(defrost, 0))
        assertEquals(R.drawable.widget_button_bg, WidgetTheme.backgroundRes(defrost, null))
    }

    @Test
    fun `slot paints active only on its own restore value`() {
        assertEquals(R.drawable.widget_button_slot, WidgetTheme.backgroundRes(memory, 760219140))
        assertEquals(R.drawable.widget_button_bg, WidgetTheme.backgroundRes(memory, 760219139))
        assertEquals(R.drawable.widget_button_bg, WidgetTheme.backgroundRes(memory, null))
    }
}
