package com.binarysimple.hvacwidget

import android.util.Log

/**
 * The widget's own diagnostics (standalone app, D-008 — no :app logging): logcat lines under
 * one tag plus a small in-memory ring, kept so a future GU round can dump the widget's side
 * of the story the same way HVAC_Log tells the panel's.
 */
object WidgetLog {

    private const val TAG = "HvacWidget"
    private const val RING_CAPACITY = 200

    private val ring = ArrayDeque<String>()

    fun i(message: String) {
        Log.i(TAG, message)
        remember("I", message)
    }

    /** Poll-path chatter: visible in logcat, not remembered — polls would eat the ring. */
    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun e(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
        remember("E", if (error == null) message else "$message: $error")
    }

    /** The remembered lines, oldest first (a future dump shares this format). */
    fun recent(): List<String> = synchronized(ring) { ring.toList() }

    private fun remember(level: String, message: String) {
        synchronized(ring) {
            ring.addLast("$level $message")
            while (ring.size > RING_CAPACITY) ring.removeFirst()
        }
    }
}
