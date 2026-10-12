package com.andrerinas.openheadunit.geely.hvac

import android.os.Handler
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Owns the status snapshot cycle (F010): the 2-second poll through the port's quiet path,
 * change logging, and the optimistic local writes after successful commands (the poller
 * confirms or corrects them). Everything the old controller's status region did — collected
 * in one place, with the "is the panel already detached" guard applied exactly once instead
 * of at every post.
 *
 * Threading: reads run on [executor] (the panel's single port thread), snapshots and
 * [overwrite] land on [mainHandler]; [onSnapshot] fires on the main thread after every
 * change (poll or optimistic write), which is the panel's single repaint trigger.
 */
class PanelStatusStore(
    private val port: CarFunctionPort,
    private val model: Hvac.CarModel,
    private val executor: Executor,
    private val mainHandler: Handler,
    private val isDetached: () -> Boolean,
) {

    /** Called on the main thread after every snapshot change (poll or optimistic write). */
    var onSnapshot: ((Map<String, Int?>) -> Unit)? = null

    /** Last known raw property values by status key; main thread only. Drives every button. */
    var lastStatus: Map<String, Int?> = emptyMap()
        private set

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (isDetached()) return
            try {
                executor.execute {
                    val next = Hvac.readStatus(port, model)
                    mainHandler.post {
                        if (isDetached()) return@post
                        applyStatus(next, logChanges = true)
                    }
                    if (!isDetached()) mainHandler.postDelayed(this, POLL_INTERVAL_MS)
                }
            } catch (_: RejectedExecutionException) {
                // detach() shut the executor down between the check and this execute.
            }
        }
    }

    /** Applies the first, pre-read snapshot: the first read paints silently, no change lines. */
    fun applyInitial(next: Map<String, Int?>) = applyStatus(next, logChanges = false)

    /** Starts the polling cycle; the first poll runs immediately after the initial snapshot. */
    fun startPolling() {
        mainHandler.post(pollRunnable)
    }

    fun stopPolling() {
        mainHandler.removeCallbacks(pollRunnable)
    }

    /** Optimistic local write after a successful command; the poller confirms or corrects. */
    fun overwrite(key: String, value: Int) {
        lastStatus = LinkedHashMap(lastStatus).apply { put(key, value) }
        onSnapshot?.invoke(lastStatus)
    }

    /** Applies a fresh snapshot: the first read paints silently, later ones log their changes. */
    private fun applyStatus(next: Map<String, Int?>, logChanges: Boolean) {
        if (logChanges && lastStatus.isNotEmpty()) {
            for (key in Hvac.changedKeys(lastStatus, next)) {
                HvacLog.i("status: $key ${lastStatus[key] ?: "none"} -> ${next[key] ?: "none"}")
            }
        }
        lastStatus = next
        onSnapshot?.invoke(lastStatus)
    }

    companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}
