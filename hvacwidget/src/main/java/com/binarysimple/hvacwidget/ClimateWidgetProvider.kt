package com.binarysimple.hvacwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.binarysimple.hvacwidget.functions.WidgetFunctions
import com.binarysimple.hvacwidget.functions.WidgetSettings
import com.binarysimple.hvacwidget.render.WidgetRenderer
import com.binarysimple.hvacwidget.service.StatusPollService

/**
 * The widget's face (F012): a stock AppWidgetProvider — the OneOS launcher is a standard
 * AppWidgetHost and lists this receiver through the system AppWidgetService
 * (docs/hvac-widget-app.md 2), no Geely-specific registration anywhere.
 *
 * Render goes through the registry ([WidgetRenderer]): text, state background and tap intent
 * per [WidgetFunctions.all]. Taps broadcast back here and are executed by
 * [WidgetCommandRunner] off the main thread; the poll service keeps statuses current.
 */
class ClimateWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        StatusPollService.start(context)
        val settings = WidgetSettings(context)
        val views = renderGuarded(context, settings)
        for (appWidgetId in appWidgetIds) {
            manager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        when {
            action == ACTION_BUTTON_TAP -> {
                val buttonId = intent.getStringExtra(EXTRA_BUTTON_ID) ?: return
                val model = WidgetSettings(context).carModel
                val spec = WidgetFunctions.byId(model, buttonId) ?: return
                WidgetCommandRunner.tap(context, spec)
            }
            action.startsWith(ACTION_PREFIX) -> pushAll(context)
        }
    }

    companion object {
        private const val ACTION_PREFIX = "com.binarysimple.hvacwidget.action."
        const val ACTION_BUTTON_TAP = ACTION_PREFIX + "BUTTON_TAP"
        const val EXTRA_BUTTON_ID = "button_id"

        /** Tap intent back to this provider, one per function (immutable: widget remotes). */
        fun tapIntent(context: Context, buttonId: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            buttonId.hashCode(),
            Intent(context, ClimateWidgetProvider::class.java)
                .setAction(ACTION_BUTTON_TAP)
                .putExtra(EXTRA_BUTTON_ID, buttonId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Re-render every placed instance from the registry (taps, poller, boot). */
        fun pushAll(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, ClimateWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val settings = WidgetSettings(appContext)
            val views = renderGuarded(appContext, settings)
            for (appWidgetId in ids) {
                manager.updateAppWidget(appWidgetId, views)
            }
        }

        /**
         * Degradation net (canon rule 8): if the registry render ever throws, the bare layout
         * is applied instead — the launcher keeps a valid (if inert) widget and the desktop
         * never sees an apply-time exception, so the widget cannot wedge itself into a
         * crash-loop or become unremovable.
         */
        private fun renderGuarded(context: Context, settings: WidgetSettings): RemoteViews =
            try {
                WidgetRenderer.render(
                    context, settings.carModel, WidgetStatus.snapshot, settings.lastMemoryRestore,
                )
            } catch (e: Throwable) {
                WidgetLog.e("render failed — falling back to the bare layout", e)
                RemoteViews(context.packageName, R.layout.climate_widget)
            }
    }
}
