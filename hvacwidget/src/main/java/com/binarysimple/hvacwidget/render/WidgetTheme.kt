package com.binarysimple.hvacwidget.render

import com.binarysimple.hvacwidget.R
import com.binarysimple.hvacwidget.functions.FunctionKind
import com.binarysimple.hvacwidget.functions.FunctionSpec

/**
 * Status painting of the widget buttons: background shapes per display state — the level
 * colors follow the :app panel's palette (PanelMetrics: gray off, green/orange/red for
 * levels 1/2/3), toggles light green when on, the active memory slot blue. RemoteViews
 * cannot recolor a shape in place, so states switch whole drawables via
 * `setBackgroundResource` — the rounded corners survive every repaint.
 *
 * Pure mapping over the registry spec — JVM-testable.
 */
object WidgetTheme {

    fun backgroundRes(spec: FunctionSpec, raw: Int?): Int = when (spec.kind) {
        FunctionKind.LEVELS -> when (spec.levelOf(raw)) {
            3 -> R.drawable.widget_button_l3
            2 -> R.drawable.widget_button_l2
            1 -> R.drawable.widget_button_l1
            else -> R.drawable.widget_button_bg
        }
        FunctionKind.TOGGLE -> if (spec.isActive(raw)) R.drawable.widget_button_l1 else R.drawable.widget_button_bg
        FunctionKind.SLOT -> if (spec.slotIndexFromStatus(raw) >= 0) R.drawable.widget_button_slot else R.drawable.widget_button_bg
    }
}
