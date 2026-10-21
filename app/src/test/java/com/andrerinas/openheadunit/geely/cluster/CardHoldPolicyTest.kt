package com.andrerinas.openheadunit.geely.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sticky-card hold (S015 Task 4, candidate A1): the grace only mutes tick noise, the
 * hold keeps the card through the unit's long PLAYING⇄STOPPED pauses (round 2026-10-21:
 * 10-90 s against a 2 s grace), and only a hold that ran out since the last valid read
 * releases the card. The transitions log once per phase — the car-log timeline the
 * fix-round reads depends on them.
 */
class CardHoldPolicyTest {

    private class Harness(graceMs: Long = 2_000L, holdMs: Long = 60_000L) {
        val logs = mutableListOf<String>()
        val policy = CardHoldPolicy(graceMs, holdMs) { logs.add(it) }

        fun valid(t: Long) = policy.validTick(t)
        fun missing(t: Long) = policy.missingTick(t)
        fun reset() = policy.reset()
    }

    private lateinit var h: Harness

    @Test
    fun `never held - a missing read releases at once with no story`() {
        h = Harness()
        assertTrue(h.logs.isEmpty())
        assertEquals(CardHoldPolicy.Decision.RELEASE, h.missing(1000))
        assertTrue(h.logs.isEmpty())
    }

    @Test
    fun `grace stays silent and holds`() {
        h = Harness()
        h.valid(1000)
        assertEquals(CardHoldPolicy.Decision.HOLD, h.missing(1500))
        // Grace entry logs once; staying silent inside is the point.
        assertEquals(1, h.logs.size)
        assertTrue(h.logs[0].contains("short session gap"))
        assertEquals(CardHoldPolicy.Decision.HOLD, h.missing(2000))
        assertEquals(CardHoldPolicy.Decision.HOLD, h.missing(2900))
        assertEquals(1, h.logs.size)
    }

    @Test
    fun `hold starts when the grace runs out and logs once`() {
        h = Harness()
        h.valid(10_000)
        assertEquals(CardHoldPolicy.Decision.HOLD, h.missing(12_000))
        assertEquals(1, h.logs.size)
        assertTrue(h.logs[0].contains("grace over"))
        assertEquals(CardHoldPolicy.Decision.HOLD, h.missing(69_999))
        assertEquals(1, h.logs.size)
    }

    @Test
    fun `hold expires at its own boundary with duration logged`() {
        h = Harness()
        h.valid(10_000)
        h.missing(12_000)
        assertEquals(CardHoldPolicy.Decision.RELEASE, h.missing(70_000))
        assertTrue(h.logs.any { it.contains("hold expired after 60 s") })
        // Released stays released; later missing reads no longer re-log.
        assertEquals(CardHoldPolicy.Decision.RELEASE, h.missing(71_000))
        val after = h.logs.size
        assertEquals(CardHoldPolicy.Decision.RELEASE, h.missing(80_000))
        assertEquals(after, h.logs.size)
    }

    @Test
    fun `a valid read restarts the clock`() {
        h = Harness()
        h.valid(1000)
        assertEquals(CardHoldPolicy.Decision.HOLD, h.missing(3000))
        h.valid(60_000)
        h.missing(61_000)
        // Same phase it was in, but the new gap logs its own line anew.
        assertEquals(2, h.logs.size)
        assertTrue(h.logs[1].contains("short session gap"))
        assertEquals(CardHoldPolicy.Decision.HOLD, h.missing(61_500))
        assertEquals(2, h.logs.size)
    }

    @Test
    fun `reset re-arms the immediate release`() {
        h = Harness()
        h.valid(1000)
        h.missing(1500)
        val before = h.logs.size
        h.reset()
        assertEquals(CardHoldPolicy.Decision.RELEASE, h.missing(31_000))
        assertEquals(before, h.logs.size)
    }
}
