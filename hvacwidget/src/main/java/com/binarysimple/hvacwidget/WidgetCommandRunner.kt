package com.binarysimple.hvacwidget

import android.content.Context
import com.binarysimple.hvacwidget.car.EcarxWidgetPort
import com.binarysimple.hvacwidget.functions.FunctionKind
import com.binarysimple.hvacwidget.functions.FunctionSpec
import com.binarysimple.hvacwidget.functions.WidgetSettings
import com.binarysimple.hvacwidget.seat.SeatMemory
import java.util.concurrent.Executors

/**
 * Executes widget taps by the function's [FunctionKind] (S010): a LEVELED button cycles its
 * level, a TOGGLE flips, a SLOT (seat memory) writes its restore value. Taps are optimistic —
 * an accepted write is folded into [WidgetStatus] immediately and the poller confirms or
 * corrects.
 *
 * Threading: onReceive runs on the main thread, binder traffic does not — one shared worker
 * serializes connect/write/push. The port object itself is safe from any thread once
 * connected, so the poller and this runner share it.
 */
object WidgetCommandRunner {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hvacwidget-command")
    }

    fun tap(context: Context, spec: FunctionSpec) {
        val appContext = context.applicationContext
        executor.execute {
            if (!EcarxWidgetPort.available) EcarxWidgetPort.connect(appContext)
            val current = WidgetStatus.snapshot[spec.id]
                ?: EcarxWidgetPort.getIntQuiet(spec.propertyId, spec.areaId)
            val value = commandValue(spec, current)
            if (value == null) {
                WidgetLog.e("tap ${spec.id}: no command for current=$current — dropped")
                return@execute
            }
            val ok = EcarxWidgetPort.available && EcarxWidgetPort.setInt(spec.propertyId, spec.areaId, value)
            if (ok) {
                WidgetStatus.overwrite(spec.id, value)
                if (spec.kind == FunctionKind.SLOT) {
                    // The reference's "selected slot" paint: when the car gives no answer,
                    // the last restored slot stays highlighted.
                    WidgetSettings(appContext).lastMemoryRestore = value
                }
            }
            WidgetLog.i("tap ${spec.id}: current=$current -> write=$value ok=$ok")
            ClimateWidgetProvider.pushAll(appContext)
        }
    }

    private fun commandValue(spec: FunctionSpec, raw: Int?): Int? = when (spec.kind) {
        FunctionKind.LEVELS -> spec.nextLevelValue(spec.levelOf(raw))
        FunctionKind.TOGGLE -> spec.toggleValue(!spec.isActive(raw))
        FunctionKind.SLOT -> SeatMemory.restoreValue(spec)
    }
}
