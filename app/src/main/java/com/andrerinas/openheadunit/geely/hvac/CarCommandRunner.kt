package com.andrerinas.openheadunit.geely.hvac

import android.content.Context
import android.os.Handler
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.geely.panel.ActionNode
import com.andrerinas.openheadunit.utils.ToastUtils
import java.util.concurrent.Executor

/**
 * Executes panel commands by the function's [FunctionKind] (F010) — the four old methods with
 * their parallel (key, autoKey, propertyId, …) tuples collapsed into one kind dispatch over
 * the node's [CarFunctionSpec], semantics preserved 1:1:
 *
 *  - a tap on a LEVELED button cycles its level; while the zone runs in auto, the FIRST tap
 *    leaves auto and continues the cycle from the auto's effective level (the masked manual
 *    level never leaks into the display); cycling down to off leaves everything off;
 *  - a tap on a TOGGLE button flips it (defrost 0/1, recirculation with its ON/OFF codes);
 *  - the AUTO long-press belongs to [CarAutoToggle] (F008/S008): the start ladder judged by
 *    the car's own answer (round 4 caught a silently ignored write the port had reported as
 *    ok), the busy zone button while a verdict is in flight, and the probe gate — an auto
 *    function the port reported unsupported is not written at all.
 *
 * Taps are optimistic — a successful write is folded into the status snapshot immediately
 * (PanelStatusStore.overwrite) and the poller confirms or corrects it; the AUTO long-press is
 * the exception (it moves only when the car confirms).
 *
 * Threading: tap/longPress on the main thread; the port runs on [executor]; results and
 * toasts hop back through [mainHandler] behind the single detached guard.
 */
class CarCommandRunner(
    private val context: Context,
    private val port: CarFunctionPort,
    private val executor: Executor,
    private val mainHandler: Handler,
    private val isDetached: () -> Boolean,
    private val store: PanelStatusStore,
    /** Configured auto-enter level per function family (the GEELY settings tab), 1..3. */
    autoLevelHint: (CarFunctionSpec) -> Int,
) {

    private val autoToggle = CarAutoToggle(context, port, executor, mainHandler, isDetached, store, autoLevelHint)

    /** Main-thread notice that a button's busy state changed; surfaced for the coordinator. */
    var onPendingChanged: ((id: String, pending: Boolean) -> Unit)?
        get() = autoToggle.onPendingChanged
        set(value) { autoToggle.onPendingChanged = value }

    /** Whether taps and long-presses on this function are ignored (its auto verdict is in flight). */
    fun isPending(id: String): Boolean = autoToggle.isPending(id)

    fun tap(node: ActionNode<CarFunctionSpec>) {
        when (node.function.kind) {
            FunctionKind.LEVELS -> cycleLevels(node)
            FunctionKind.TOGGLE -> toggle(node)
        }
    }

    /** Long-press toggles AUTO (F008) — the verdict-gated ladder of [CarAutoToggle]. */
    fun longPress(node: ActionNode<CarFunctionSpec>) = autoToggle.longPress(node)

    /** Tap on a level button: auto-first semantics, then the plain cycle (see class doc). */
    private fun cycleLevels(node: ActionNode<CarFunctionSpec>) {
        val spec = node.function
        val table = spec.levels ?: return
        val snapshot = store.lastStatus
        val autoSpec = spec.auto
        val autoLevel = autoSpec?.levelOf(snapshot[autoSpec.id]) ?: 0
        if (autoSpec != null && autoLevel > 0) {
            val next = Hvac.nextLevelOrdinal(autoLevel, table)
            val value = table[next]
            executor.execute {
                val autoOff = port.setInt(autoSpec.propertyId, autoSpec.areaId, 0)
                val ok = port.setInt(spec.propertyId, spec.areaId, value)
                mainHandler.post {
                    if (isDetached()) return@post
                    if (autoOff) store.overwrite(autoSpec.id, 0)
                    if (ok) store.overwrite(spec.id, value)
                    toastLevel(node.labelRes, next, ok)
                }
            }
        } else {
            val current = spec.levelOf(snapshot[spec.id])
            val next = Hvac.nextLevelOrdinal(current, table)
            val value = table[next]
            executor.execute {
                val ok = port.setInt(spec.propertyId, spec.areaId, value)
                mainHandler.post {
                    if (isDetached()) return@post
                    if (ok) store.overwrite(spec.id, value)
                    toastLevel(node.labelRes, next, ok)
                }
            }
        }
    }

    /** Tap on a toggle button: defrost 0/1, recirculation ON/OFF codes. */
    private fun toggle(node: ActionNode<CarFunctionSpec>) {
        val spec = node.function
        val turnOn = !spec.isActive(store.lastStatus[spec.id])
        val value = spec.toggleValue(turnOn)
        executor.execute {
            val ok = port.setInt(spec.propertyId, spec.areaId, value)
            mainHandler.post {
                if (isDetached()) return@post
                if (ok) store.overwrite(spec.id, value)
                toastToggle(node.labelRes, turnOn, ok)
            }
        }
    }

    private fun toastLevel(labelRes: Int, ordinal: Int, ok: Boolean) {
        val label = context.getString(labelRes)
        val text = if (!ok) {
            context.getString(R.string.hvac_cmd_failed)
        } else if (ordinal <= 0) {
            context.getString(R.string.hvac_toast_off, label)
        } else {
            context.getString(R.string.hvac_toast_level, label, ordinal)
        }
        ToastUtils.showToast(context, text, force = true)
    }

    private fun toastToggle(labelRes: Int, on: Boolean, ok: Boolean) {
        val label = context.getString(labelRes)
        val text = if (!ok) context.getString(R.string.hvac_cmd_failed)
        else context.getString(if (on) R.string.hvac_toast_on else R.string.hvac_toast_off, label)
        ToastUtils.showToast(context, text, force = true)
    }
}
