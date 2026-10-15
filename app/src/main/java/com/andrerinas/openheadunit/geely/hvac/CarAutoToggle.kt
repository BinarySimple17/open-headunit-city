package com.andrerinas.openheadunit.geely.hvac

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.geely.panel.ActionNode
import com.andrerinas.openheadunit.utils.ToastUtils
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * The AUTO long-press of a leveled function (F008/S008), split out of [CarCommandRunner]:
 * entering auto runs the start ladder ([CarFunctionSpec.autoStartAttempts]) — and the verdict
 * is the CAR's, not the port's. Round 4 caught the driver seat silently ignoring an auto-code
 * write the port had reported as ok, so a rung counts only when the zone answers in auto
 * ([CarFunctionSpec.readsAuto]) within [CONFIRM_WINDOW_MS]; until a rung confirms or every
 * rung times out, the zone button is busy — dimmed via [onPendingChanged], gestures ignored
 * via [isPending]. A zone the car answers in auto switches off COMPLETELY (every auto
 * entrance back to zero, so no masked heating survives), and the off toast waits for the
 * AUTO property to answer off again ([CarFunctionSpec.readsAutoOff]). An auto function the
 * port's probe reported unsupported is not written at all (the probe predicts the write:
 * wheel_auto came back notavailable AND rejected).
 *
 * The toggle overwrites nothing optimistic: its state moves only when the poller delivers
 * the car's own answer, so the display never runs ahead of what the car actually did.
 *
 * Threading: the state machine lives on the main thread; the port writes run on [executor];
 * results hop back through [mainHandler] behind the single detached guard. Detach clears the
 * handler, so pending checks die with the panel — a verdict has nowhere to land once the
 * controller is gone.
 */
class CarAutoToggle(
    private val context: Context,
    private val port: CarFunctionPort,
    private val executor: Executor,
    private val mainHandler: Handler,
    private val isDetached: () -> Boolean,
    private val store: PanelStatusStore,
    /** Configured auto-enter level per function family (the GEELY settings tab), 1..3. */
    private val autoLevelHint: (CarFunctionSpec) -> Int,
) {

    /** Function ids with an auto verdict in flight; the coordinator dims their buttons. */
    private val pending = mutableSetOf<String>()

    /** Main-thread notice that a button's busy state changed; set up by the coordinator. */
    var onPendingChanged: ((id: String, pending: Boolean) -> Unit)? = null

    /** Whether taps and long-presses on this function are ignored (its auto verdict is in flight). */
    fun isPending(id: String): Boolean = id in pending

    /**
     * Long-press toggles AUTO. Which side of the toggle the zone is on is the car's answer
     * too ([CarFunctionSpec.readsAuto]): the round-4 answer code sits on the manual property
     * as well, where the level table never sees it.
     */
    fun longPress(node: ActionNode<CarFunctionSpec>) {
        val spec = node.function
        val autoSpec = spec.auto ?: return
        if (!port.isFunctionSupported(autoSpec.propertyId, autoSpec.areaId)) {
            HvacLog.i("auto: ${autoSpec.id} unavailable (probe verdict)")
            toastUnavailable()
            return
        }
        if (spec.id in pending) return
        val inAuto = spec.readsAuto(store.lastStatus[spec.id], store.lastStatus[autoSpec.id])
        if (inAuto) autoOff(node, autoSpec) else autoOn(node, autoSpec)
    }

    /**
     * Long-press while the zone answers in auto: every auto entrance back to zero, then the
     * car's verdict — the writes report only what the port accepted, the toast waits for the
     * AUTO property to answer off again.
     */
    private fun autoOff(node: ActionNode<CarFunctionSpec>, autoSpec: CarFunctionSpec) {
        val spec = node.function
        val attempts = node.function.autoOffAttempts()
        setPending(spec.id, true)
        try {
            executor.execute {
                val verdicts = attempts.map { attempt -> attempt to port.setInt(attempt.propertyId, attempt.areaId, attempt.value) }
                val accepted = verdicts.count { it.second }
                val detail = verdicts.joinToString(" ") { "${it.first.label}=${if (it.second) "ok" else "rejected"}" }
                HvacLog.i("auto: ${autoSpec.id} -> off writes ($accepted/${attempts.size} accepted: $detail)")
                mainHandler.post {
                    if (isDetached()) return@post
                    awaitOffConfirmation(node, autoSpec, SystemClock.elapsedRealtime())
                }
            }
        } catch (_: RejectedExecutionException) {
            // detach() shut the port thread down mid-verdict; the panel is gone with it.
        }
    }

    /** Long-press with auto off: run the start ladder at the manual level, or the configured one. */
    private fun autoOn(node: ActionNode<CarFunctionSpec>, autoSpec: CarFunctionSpec) {
        val spec = node.function
        val manual = spec.levelOf(store.lastStatus[spec.id])
        val chosen = if (manual > 0) manual else autoLevelHint(spec)
        val table = autoSpec.levels ?: return
        val level = chosen.coerceIn(1, table.lastIndex)
        val attempts = spec.autoStartAttempts(level)
        if (attempts.isEmpty()) return
        setPending(spec.id, true)
        runEnterRung(node, autoSpec, attempts, 0)
    }

    /**
     * One ladder rung: the write itself on the port thread, the verdict on the main thread.
     * An API-ok write is only a hypothesis (round 4: the driver seat ignored the auto code
     * twice while the port said ok), so the rung counts only when [CarFunctionSpec.readsAuto]
     * turns true within the window — otherwise the next rung gets its chance, and a rung the
     * port itself rejected never starts a wait at all.
     */
    private fun runEnterRung(
        node: ActionNode<CarFunctionSpec>,
        autoSpec: CarFunctionSpec,
        attempts: List<CarWriteAttempt>,
        index: Int,
    ) {
        val attempt = attempts[index]
        try {
            executor.execute {
                val ok = port.setInt(attempt.propertyId, attempt.areaId, attempt.value)
                HvacLog.i("auto: ${autoSpec.id} try ${attempt.label} prop=${attempt.propertyId} " +
                    "area=${attempt.areaId} value=${attempt.value} -> ${if (ok) "ok" else "rejected"}")
                mainHandler.post {
                    if (isDetached()) return@post
                    val startedAt = SystemClock.elapsedRealtime()
                    if (ok) awaitEnterConfirmation(node, autoSpec, attempts, index, startedAt)
                    else nextEnterRung(node, autoSpec, attempts, index + 1)
                }
            }
        } catch (_: RejectedExecutionException) {
            // detach() shut the port thread down mid-verdict; the panel is gone with it.
        }
    }

    private fun awaitEnterConfirmation(
        node: ActionNode<CarFunctionSpec>,
        autoSpec: CarFunctionSpec,
        attempts: List<CarWriteAttempt>,
        index: Int,
        startedAt: Long,
    ) {
        val spec = node.function
        val attempt = attempts[index]
        val check = object : Runnable {
            override fun run() {
                if (isDetached()) return
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (spec.readsAuto(store.lastStatus[spec.id], store.lastStatus[autoSpec.id])) {
                    HvacLog.i("auto: ${autoSpec.id} ${attempt.label} confirmed by car in $elapsed ms")
                    setPending(spec.id, false)
                    toastToggle(node.labelRes, on = true, ok = true)
                    return
                }
                if (elapsed < CONFIRM_WINDOW_MS) {
                    mainHandler.postDelayed(this, CONFIRM_TICK_MS)
                    return
                }
                HvacLog.i("auto: ${autoSpec.id} ${attempt.label} unconfirmed after $CONFIRM_WINDOW_MS ms")
                nextEnterRung(node, autoSpec, attempts, index + 1)
            }
        }
        mainHandler.postDelayed(check, CONFIRM_TICK_MS)
    }

    private fun nextEnterRung(
        node: ActionNode<CarFunctionSpec>,
        autoSpec: CarFunctionSpec,
        attempts: List<CarWriteAttempt>,
        index: Int,
    ) {
        if (index < attempts.size) {
            runEnterRung(node, autoSpec, attempts, index)
            return
        }
        HvacLog.i("auto: ${autoSpec.id} enter failed — none of ${attempts.size} rungs confirmed")
        setPending(node.function.id, false)
        toastToggle(node.labelRes, on = true, ok = false)
    }

    private fun awaitOffConfirmation(
        node: ActionNode<CarFunctionSpec>,
        autoSpec: CarFunctionSpec,
        startedAt: Long,
    ) {
        val spec = node.function
        val check = object : Runnable {
            override fun run() {
                if (isDetached()) return
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (spec.readsAutoOff(store.lastStatus[autoSpec.id])) {
                    HvacLog.i("auto: ${autoSpec.id} off confirmed by car in $elapsed ms")
                    setPending(spec.id, false)
                    toastToggle(node.labelRes, on = false, ok = true)
                    return
                }
                if (elapsed < CONFIRM_WINDOW_MS) {
                    mainHandler.postDelayed(this, CONFIRM_TICK_MS)
                    return
                }
                HvacLog.i("auto: ${autoSpec.id} off unconfirmed after $CONFIRM_WINDOW_MS ms")
                setPending(spec.id, false)
                toastToggle(node.labelRes, on = false, ok = false)
            }
        }
        mainHandler.postDelayed(check, CONFIRM_TICK_MS)
    }

    private fun setPending(id: String, value: Boolean) {
        if (value) pending.add(id) else pending.remove(id)
        onPendingChanged?.invoke(id, value)
    }

    private fun toastToggle(labelRes: Int, on: Boolean, ok: Boolean) {
        val label = context.getString(labelRes)
        val text = if (!ok) context.getString(R.string.hvac_cmd_failed)
        else context.getString(if (on) R.string.hvac_toast_on else R.string.hvac_toast_off, label)
        ToastUtils.showToast(context, text, force = true)
    }

    private fun toastUnavailable() {
        ToastUtils.showToast(context, context.getString(R.string.hvac_fn_unavailable), force = true)
    }

    companion object {
        /**
         * How long a rung waits for the car to answer before it counts as silently rejected.
         * The status poll runs every [PanelStatusStore.POLL_INTERVAL_MS] and a confirmed
         * entrance showed up ~1.7 s after the write — the window covers a full poll cycle
         * plus that latency, so a silent rejection costs the ladder 2.5 s per rung, honest.
         */
        private const val CONFIRM_WINDOW_MS = 2_500L

        /** Granularity of the confirmation checks; the snapshot itself updates on the poll. */
        private const val CONFIRM_TICK_MS = 250L
    }
}
