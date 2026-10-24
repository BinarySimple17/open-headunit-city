package com.andrerinas.openheadunit.geely.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two round-2026-10-22 rules of the monitor's snapshot cache (BL-004 facts 17-18).
 * D: the old HashMap eviction took the first ITERATOR key — once the map filled it ate
 * the CURRENT track's entry and the monitor re-ran the whole first-sight path (donor +
 * jpeg re-encode + log flood) every tick of the drive. The access-order cache must keep
 * the just-stored key and drop the least recently used OTHER one. E: the ~1 s stripped
 * next-track announcement (album and duration both missing) must read as degraded —
 * publishing it cost a second uuid, a double push per track change.
 */
class TrackSnapshotPolicyTest {

    @Test
    fun `cache stays bounded at MAX_TRACKS`() {
        val cache = TrackSnapshotPolicy.newSnapshotCache<String>()
        repeat(TrackSnapshotPolicy.MAX_TRACKS + 5) { cache["k$it"] = "v$it" }
        assertEquals(TrackSnapshotPolicy.MAX_TRACKS, cache.size)
    }

    @Test
    fun `a full cache keeps the key it just stored - no rebuild loop`() {
        val cache = TrackSnapshotPolicy.newSnapshotCache<String>()
        repeat(TrackSnapshotPolicy.MAX_TRACKS) { cache["old$it"] = "v" }
        // The evening-loop scenario: the current track's key stored into a FULL map —
        // the eviction must take the eldest OTHER key, never the one just stored.
        cache["current"] = "v"
        assertEquals(TrackSnapshotPolicy.MAX_TRACKS, cache.size)
        assertTrue("the stored key must survive its own store", "current" in cache)
        assertFalse("the eldest old key dropped", "old0" in cache)
        assertTrue("old7" in cache)
    }

    @Test
    fun `a read refreshes recency the same as a put`() {
        val cache = TrackSnapshotPolicy.newSnapshotCache<String>()
        repeat(TrackSnapshotPolicy.MAX_TRACKS) { cache["k$it"] = "v$it" }
        assertEquals("v0", cache["k0"]) // the fast path's read keeps the current track fresh
        cache["newest"] = "v"
        assertTrue("k0" in cache)
        assertFalse("the untouched eldest drops", "k1" in cache)
    }

    @Test
    fun `degraded pre-announcement is exactly album-less AND duration-less`() {
        assertTrue(TrackSnapshotPolicy.isDegradedPreAnnouncement(null, -1L))
        assertTrue(TrackSnapshotPolicy.isDegradedPreAnnouncement("", 0L))
        // Duration present (or album present) = a real read, publish it.
        assertFalse(TrackSnapshotPolicy.isDegradedPreAnnouncement("", 243_930L))
        assertFalse(TrackSnapshotPolicy.isDegradedPreAnnouncement("Illwill", 0L))
        assertFalse(TrackSnapshotPolicy.isDegradedPreAnnouncement("Illwill", 243_930L))
    }

    @Test
    fun `the source-type stamp is idempotent - a standing card cannot re-push forever`() {
        val raw = TrackSnapshot("Absolution", "Muse", "Absolution", null, 274_120L, true, "uuid-1")
        assertEquals("the raw read defaults to the online slot", TrackSnapshot.SOURCE_TYPE_ONLINE, raw.sourceType)
        val stamped = TrackSnapshotPolicy.effectiveSnapshot(raw, sourceTypeBt = true)
        assertEquals(TrackSnapshot.SOURCE_TYPE_BT, stamped.sourceType)
        // The evening-loop regression (2026-10-24): stamping the already-stamped card
        // must change nothing, so equality against the stored card holds tick after tick.
        assertEquals(stamped, TrackSnapshotPolicy.effectiveSnapshot(stamped, sourceTypeBt = true))
        assertEquals("switch off leaves the raw card untouched", raw, TrackSnapshotPolicy.effectiveSnapshot(raw, sourceTypeBt = false))
    }
}
