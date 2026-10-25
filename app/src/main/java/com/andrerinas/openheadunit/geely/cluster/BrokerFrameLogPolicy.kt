package com.andrerinas.openheadunit.geely.cluster

/**
 * What the broker observer writes to the HVAC log (BL-004 fact 41). The broker carries
 * OUR OWN 1 Hz progress mirror — the cluster service republishes every
 * updateCurrentProgress onto `ihu/info/media/progress` — so naive per-frame logging
 * floods the file at one line per second and buries the rare frames the hunt is after
 * (the stock publisher's card frames, the projection-axis switches). Pure decisions
 * with an injected clock: the bytes live in [DimBrokerWire], the socket in
 * [DimBrokerChannel].
 *
 * Rules: the progress topic is our own mirror — first frame, then once a minute at
 * most, with the count. Everything else logs on change (at most once per second per
 * topic — a burst collapses into the suppressed counter of the next line), and an
 * unchanged repeat never logs except as a once-a-minute «×N» summary. Payloads are
 * truncated: the point is WHO published WHAT and WHEN, not the full bytes.
 */
internal class BrokerFrameLogPolicy(private val clock: () -> Long = System::currentTimeMillis) {

    private class TopicState(
        var lastPayload: String? = null,
        /** Long.MIN_VALUE/2, not 0: a policy born at clock 0 must still log its FIRST frame. */
        var lastLogAt: Long = Long.MIN_VALUE / 2,
        var repeats: Int = 0,
        var suppressed: Int = 0,
        var count: Int = 0,
    )

    private val state = HashMap<String, TopicState>()

    /** One inbound frame -> the line to write (without the log prefix), or null = noise. */
    fun onFrame(topic: String, payload: String): String? {
        val s = state.getOrPut(topic) { TopicState() }
        s.count++
        val now = clock()
        val unchanged = payload == s.lastPayload
        s.lastPayload = payload
        if (topic == PROGRESS_TOPIC) {
            // Our own 1 Hz mirror republished by the service: no information per frame.
            if (now - s.lastLogAt < HEARTBEAT_MS) {
                s.repeats++
                return null
            }
            s.lastLogAt = now
            val note = if (s.repeats > 0) " ×${s.repeats + 1}" else ""
            s.repeats = 0
            return line(topic, payload, note)
        }
        if (!unchanged) {
            if (now - s.lastLogAt < CHANGE_INTERVAL_MS) {
                s.suppressed++
                return null
            }
            val suppressed = s.suppressed
            s.suppressed = 0
            s.lastLogAt = now
            return line(topic, payload, if (suppressed > 0) " (+$suppressed suppressed)" else "")
        }
        s.repeats++
        if (now - s.lastLogAt >= HEARTBEAT_MS) {
            val repeats = s.repeats
            s.repeats = 0
            s.lastLogAt = now
            return line(topic, payload, " ×$repeats unchanged")
        }
        return null
    }

    /** Totals for the observer's periodic heartbeat — per-topic counts since arming. */
    fun statsLine(): String =
        state.entries.sortedBy { it.key }.joinToString(" ") {
            "${it.key.removePrefix("ihu/")}=${it.value.count}"
        }

    private fun line(topic: String, payload: String, note: String): String {
        val shown = if (payload.length > MAX_PAYLOAD) payload.take(MAX_PAYLOAD) + "…" else payload
        return "$topic $shown$note"
    }

    private companion object {
        const val PROGRESS_TOPIC = "ihu/info/media/progress"
        const val CHANGE_INTERVAL_MS = 1_000L
        const val HEARTBEAT_MS = 60_000L
        const val MAX_PAYLOAD = 160
    }
}
