package com.andrerinas.openheadunit.geely.hvac

import android.content.Context
import android.os.Handler
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.geely.panel.ActionNode
import com.andrerinas.openheadunit.utils.ToastUtils
import java.util.concurrent.Executor

/**
 * Executes panel commands by the function's [FunctionKind] (F010): a tap on a LEVELED button
 * cycles its level, a tap on a TOGGLE button flips it (defrost 0/1, recirculation with its
 * ON/OFF codes). The AUTO long-press (F008) was removed 2026-10-17 by the planner's decision
 * (S008 rounds 4–6: the car kept silently ignoring the auto-code write for the driver zone;
 * the whole auto-heat entrance is gone — конспект §2.10).
 *
 * Taps are optimistic — a successful write is folded into the status snapshot immediately
 * (PanelStatusStore.overwrite) and the poller confirms or corrects it.
 *
 * Threading: tap on the main thread; the port runs on [executor]; results and toasts hop
 * back through [mainHandler] behind the single detached guard.
 */
class CarCommandRunner(
    private val context: Context,
    private val port: CarFunctionPort,
    private val executor: Executor,
    private val mainHandler: Handler,
    private val isDetached: () -> Boolean,
    private val store: PanelStatusStore,
) {

    fun tap(node: ActionNode<CarFunctionSpec>) {
        when (node.function.kind) {
            FunctionKind.LEVELS -> cycleLevels(node)
            FunctionKind.TOGGLE -> toggle(node)
        }
    }

    /** Tap on a level button: cycle 0 → L1 → L2 → L3 → off. */
    private fun cycleLevels(node: ActionNode<CarFunctionSpec>) {
        val spec = node.function
        val table = spec.levels ?: return
        val current = spec.levelOf(store.lastStatus[spec.id])
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
