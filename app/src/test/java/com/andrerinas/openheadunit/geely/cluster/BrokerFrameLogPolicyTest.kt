package com.andrerinas.openheadunit.geely.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The observer's noise budget (BL-004 fact 41): the broker republishes OUR OWN 1 Hz
 * progress onto `ihu/info/media/progress`, so per-frame logging would bury the HVAC log
 * at one line per second — exactly the frames the hunt needs are the rare ones. Every
 * rule of [BrokerFrameLogPolicy] is pinned here against an injected clock.
 */
class BrokerFrameLogPolicyTest {

    private var now = 0L
    private val policy = BrokerFrameLogPolicy { now }

    @Test
    fun `the first frame of a topic logs immediately`() {
        now = 5_000
        assertEquals(
            "ihu/info/phoneprojection {\"statusType\":\"noProjection\"}",
            policy.onFrame("ihu/info/phoneprojection", "{\"statusType\":\"noProjection\"}"),
        )
    }

    @Test
    fun `unchanged repeats stay silent until the minute summary`() {
        policy.onFrame("ihu/info/media/sources", "[2]")
        now += 1_000
        repeat(10) { assertNull(policy.onFrame("ihu/info/media/sources", "[2]")) }
        now += 60_000
        assertEquals("ihu/info/media/sources [2] ×11 unchanged", policy.onFrame("ihu/info/media/sources", "[2]"))
    }

    @Test
    fun `a changed payload inside one second is suppressed and counted`() {
        policy.onFrame("ihu/info/media/playback/status", "a")
        now += 200
        assertNull("too soon after the previous line", policy.onFrame("ihu/info/media/playback/status", "b"))
        now += 900 // 1.1 s since the last LOG
        val line = policy.onFrame("ihu/info/media/playback/status", "c")
        assertTrue("the suppressed burst is accounted for: $line", line!!.endsWith("(+1 suppressed)"))
    }

    @Test
    fun `our own progress mirror logs once a minute at most`() {
        policy.onFrame("ihu/info/media/progress", "1000")
        now += 1_000
        assertNull("changed AND fresh — still silent", policy.onFrame("ihu/info/media/progress", "2000"))
        now += 60_000
        assertEquals("ihu/info/media/progress 62000 ×2", policy.onFrame("ihu/info/media/progress", "62000"))
    }

    @Test
    fun `payloads are truncated for the log`() {
        val line = policy.onFrame("ihu/x", "y".repeat(300))!!
        assertTrue("topic + note + 160 chars + ellipsis only: ${line.length}", line.length < 200)
        assertTrue(line.endsWith("…"))
    }

    @Test
    fun `the stats line counts every frame per topic`() {
        policy.onFrame("ihu/a", "1")
        policy.onFrame("ihu/a", "1")
        policy.onFrame("ihu/info/media/progress", "1")
        assertEquals("a=2 info/media/progress=1", policy.statsLine())
    }
}
