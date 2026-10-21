package com.andrerinas.openheadunit.geely.cluster

import com.andrerinas.openheadunit.geely.hvac.HvacLog

/**
 * The sticky-card hold (S015 Task 4, candidate A1 — BL-004 facts 7-9): this unit's own media
 * sessions flap PLAYING⇄STOPPED for pauses the 2 s grace never mentions (round 2026-10-21:
 * 5 own/clear cycles in 4.5 minutes, pauses 10-90 s), and every flap used to clear the card
 * straight after the grace — the slow half of the flicker. The monitor now feeds each READ
 * here: a read that saw a playing session with metadata re-arms the clock; a read with no
 * such session answers HOLD — keep the last published card untouched — until the whole
 * [holdMs] has run out since the last valid read; then RELEASE clears as before (as do
 * switch off and disarm — those paths never consult [CardHoldPolicy]). Pure decision logic,
 * no Android, so the boundaries stay JVM-pinned ([CardHoldPolicyTest]).
 */
internal class CardHoldPolicy(
    private val graceMs: Long,
    private val holdMs: Long,
    private val log: (String) -> Unit = { HvacLog.i(it) },
) {

    /** HOLD = keep the last card as published (position frozen — nothing is re-pushed).
     *  RELEASE = nothing valid for the whole hold: clear as the old code did. */
    internal enum class Decision { HOLD, RELEASE }

    private enum class Phase { GAP, HOLD, OVER }

    private var lastValidAt = 0L
    private var phase: Phase? = null

    /** A read that saw a playing session with metadata: the hold clock restarts here. */
    internal fun validTick(nowMs: Long) {
        if (nowMs > lastValidAt) lastValidAt = nowMs
        phase = null
    }

    /** A read with no playing session (or none with readable metadata). Never-held state
     *  (monitor just started, or reset) releases immediately, exactly like the old code. */
    internal fun missingTick(nowMs: Long): Decision {
        if (lastValidAt <= 0L) return Decision.RELEASE
        val elapsed = nowMs - lastValidAt
        val current = when {
            elapsed < graceMs -> Phase.GAP
            elapsed < holdMs -> Phase.HOLD
            else -> Phase.OVER
        }
        if (current != phase) {
            phase = current
            when (current) {
                Phase.GAP -> log("cluster.bt: short session gap — holding the card")
                Phase.HOLD ->
                    log("cluster.bt: session still down — holding the card (grace over, hold in force)")
                Phase.OVER ->
                    log("cluster.bt: hold expired after ${elapsed / 1000} s since the last valid read — clearing")
            }
        }
        return if (current == Phase.OVER) Decision.RELEASE else Decision.HOLD
    }

    /** Stop: the clock and the phase forget their last track (fresh start releases at once). */
    internal fun reset() {
        lastValidAt = 0L
        phase = null
    }
}
