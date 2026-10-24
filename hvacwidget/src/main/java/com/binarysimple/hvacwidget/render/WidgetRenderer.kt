package com.binarysimple.hvacwidget.render

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.binarysimple.hvacwidget.ClimateWidgetProvider
import com.binarysimple.hvacwidget.R
import com.binarysimple.hvacwidget.functions.FunctionKind
import com.binarysimple.hvacwidget.functions.FunctionSpec
import com.binarysimple.hvacwidget.functions.WidgetFunctions

/**
 * Builds the widget's RemoteViews FROM the registry (S010 layering): one pass over
 * [WidgetFunctions.all] — text, state background, tap intent per spec — so a new function
 * never touches this file. Memory buttons beyond the model's slot ladder are hidden
 * (Cityray renders two of the three layout slots).
 */
object WidgetRenderer {

    fun render(
        context: Context,
        model: WidgetFunctions.Model,
        status: Map<String, Int?>,
        lastMemoryRestore: Int = 0,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.climate_widget)
        for (spec in WidgetFunctions.all(model)) {
            val raw = status[spec.id] ?: memoryFallback(spec, lastMemoryRestore)
            views.setTextViewText(spec.viewId, context.getString(spec.labelRes))
            views.setInt(spec.viewId, "setBackgroundResource", WidgetTheme.backgroundRes(spec, raw))
            views.setOnClickPendingIntent(spec.viewId, ClimateWidgetProvider.tapIntent(context, spec.id))
            views.setViewVisibility(spec.viewId, View.VISIBLE)
        }
        // Visibility persists between RemoteViews updates — re-assert it every render.
        for (viewId in WidgetFunctions.memoryViewIds.drop(model.memorySlots.size)) {
            views.setViewVisibility(viewId, View.GONE)
        }
        return views
    }

    /** No poll answer for a memory slot: paint the last restored slot (reference's "selected slot"). */
    private fun memoryFallback(spec: FunctionSpec, lastMemoryRestore: Int): Int? =
        if (spec.kind == FunctionKind.SLOT && lastMemoryRestore != 0) lastMemoryRestore else null
}
