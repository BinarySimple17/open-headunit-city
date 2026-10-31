package com.andrerinas.openheadunit.geely.hvac

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.geely.panel.ActionNode
import com.andrerinas.openheadunit.utils.ToastUtils
import java.util.concurrent.Executor

/**
 * Executes panel commands by the function's [FunctionKind] (F010): a tap on a LEVELED button
 * cycles its level — for the seat-heat zones through the F015 tap timer (quick taps walk the
 * ladder, a tap after [Hvac.TAP_OFF_WINDOW_MS] switches off) — a tap on a TOGGLE button flips
 * it, a tap on a SLOT button (F015 seat memory) writes its restore value. The AUTO long-press
 * (F008) was removed 2026-10-17 by the planner's decision (S008 rounds 4–6: the car kept
 * silently ignoring the auto-code write for the driver zone; the whole auto-heat entrance is
 * gone — конспект §2.10).
 *
 * Taps are optimistic — a successful write is folded into the status snapshot immediately
 * (PanelStatusStore.overwrite) and the poller confirms or corrects it. For SLOT buttons the
 * ACTIVE verdict stays with the car's read (the poller), never with the tap.
 *
 * The confirmation toasts follow the F015 setting: [HvacSettings.toastsEnabled] gates every
 * toast, the HVAC log keeps writing regardless.
 *
 * Threading: tap on the main thread; the port runs on [executor]; results and toasts hop
 * back through [mainHandler] behind the single detached guard. The tap-timer state
 * ([lastTapAt]) lives in this instance — main-thread only, so it needs no locking; the panel
 * re-creating resets it (a fresh panel's first tap counts as "after the window").
 */
class CarCommandRunner(
    private val context: Context,
    private val port: CarFunctionPort,
    private val executor: Executor,
    private val mainHandler: Handler,
    private val isDetached: () -> Boolean,
    private val store: PanelStatusStore,
    private val settings: HvacSettings,
) {

    /** Time of the last tap per status key (F015 tap timer); process-life data. */
    private val lastTapAt = HashMap<String, Long>()

    fun tap(node: ActionNode<CarFunctionSpec>) {
        when (node.function.kind) {
            FunctionKind.LEVELS -> cycleLevels(node)
            FunctionKind.TOGGLE -> toggle(node)
            FunctionKind.SLOT -> restoreSlot(node)
        }
    }

    /**
     * Tap on a seat-heat zone: with a tap window (registry data) the timed rule applies —
     * within the window the ladder walks and the timer restarts, after it (or with no tap
     * in memory) the heat switches OFF; from off a tap turns it on at L1. Without a window
     * the plain cycle stays (planner's rule is scoped to the seat zones).
     */
    private fun cycleLevels(node: ActionNode<CarFunctionSpec>) {
        val spec = node.function
        val table = spec.levels ?: return
        val window = spec.tapOffWindowMs
        val current = spec.levelOf(store.lastStatus[spec.id])
        val next = if (window != null) {
            val now = SystemClock.uptimeMillis()
            val elapsed = lastTapAt[spec.id]?.let { now - it }
            lastTapAt[spec.id] = now
            Hvac.timedTapOrdinal(current, table, elapsed)
        } else {
            Hvac.nextLevelOrdinal(current, table)
        }
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

    /**
     * Tap on a seat-position button (F015): write the slot's restore value — the seat moves
     * there; tapping the other position moves it there. The car's read-back (the poller)
     * then marks the active position. A spec without a confirmed value (model ladder empty)
     * drops the tap — the button never invents a value.
     */
    private fun restoreSlot(node: ActionNode<CarFunctionSpec>) {
        val spec = node.function
        val value = spec.slotValue
        if (value == null) {
            HvacLog.i("tap ${spec.id}: no confirmed slot value for ${settings.carModel} — dropped")
            return
        }
        val position = Hvac.memorySlotOfKey(spec.id)
        executor.execute {
            val ok = port.setInt(spec.propertyId, spec.areaId, value)
            mainHandler.post {
                if (isDetached()) return@post
                if (ok) store.overwrite(spec.id, value)
                toastPosition(node.labelRes, position, ok)
            }
        }
    }

    private fun toastLevel(labelRes: Int, ordinal: Int, ok: Boolean) {
        if (!settings.toastsEnabled) return
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
        if (!settings.toastsEnabled) return
        val label = context.getString(labelRes)
        val text = if (!ok) context.getString(R.string.hvac_cmd_failed)
        else context.getString(if (on) R.string.hvac_toast_on else R.string.hvac_toast_off, label)
        ToastUtils.showToast(context, text, force = true)
    }

    private fun toastPosition(labelRes: Int, position: Int, ok: Boolean) {
        if (!settings.toastsEnabled) return
        val label = context.getString(labelRes)
        val text = if (!ok) context.getString(R.string.hvac_cmd_failed)
        else context.getString(R.string.hvac_toast_position, label, position)
        ToastUtils.showToast(context, text, force = true)
    }
}
