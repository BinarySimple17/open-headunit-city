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
 *  - a long-press toggles AUTO (F008): a zone already in auto switches off COMPLETELY
 *    (auto 0 AND manual 0, so no masked heating survives); entering auto takes the manual
 *    level when the function is on, otherwise the configured one ([autoLevelHint]).
 *
 * Taps are optimistic — a successful write is folded into the status snapshot immediately
 * (PanelStatusStore.overwrite) and the poller confirms or corrects it.
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
    private val autoLevelHint: (CarFunctionSpec) -> Int,
) {

    fun tap(node: ActionNode<CarFunctionSpec>) {
        when (node.function.kind) {
            FunctionKind.LEVELS -> cycleLevels(node)
            FunctionKind.TOGGLE -> toggle(node)
        }
    }

    fun longPress(node: ActionNode<CarFunctionSpec>) {
        val autoSpec = node.function.auto ?: return
        if (autoSpec.levelOf(store.lastStatus[autoSpec.id]) > 0) autoOff(node, autoSpec) else autoOn(node, autoSpec)
    }

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

    /** Long-press while auto runs: auto to 0 AND the manual level to 0 — fully off. */
    private fun autoOff(node: ActionNode<CarFunctionSpec>, autoSpec: CarFunctionSpec) {
        val spec = node.function
        executor.execute {
            val okAuto = port.setInt(autoSpec.propertyId, autoSpec.areaId, 0)
            val okManual = port.setInt(spec.propertyId, spec.areaId, 0)
            mainHandler.post {
                if (isDetached()) return@post
                if (okAuto) store.overwrite(autoSpec.id, 0)
                if (okManual) store.overwrite(spec.id, 0)
                HvacLog.i("auto: ${autoSpec.id} -> off (heating fully off, auto=$okAuto manual=$okManual)")
                toastToggle(node.labelRes, on = false, ok = okAuto && okManual)
            }
        }
    }

    /** Long-press with auto off: enter auto at the manual level, or the configured one. */
    private fun autoOn(node: ActionNode<CarFunctionSpec>, autoSpec: CarFunctionSpec) {
        val spec = node.function
        val manual = spec.levelOf(store.lastStatus[spec.id])
        val chosen = if (manual > 0) manual else autoLevelHint(spec)
        val table = autoSpec.levels ?: return
        val value = table[chosen.coerceIn(1, table.lastIndex)]
        executor.execute {
            val ok = port.setInt(autoSpec.propertyId, autoSpec.areaId, value)
            mainHandler.post {
                if (isDetached()) return@post
                if (ok) store.overwrite(autoSpec.id, value)
                HvacLog.i("auto: ${autoSpec.id} -> $value (${if (ok) "ok" else "rejected"})")
                toastToggle(node.labelRes, on = true, ok = ok)
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
