package com.andrerinas.openheadunit.geely.cluster

/**
 * The snapshot-cache and degraded-read policy of the Bluetooth monitor (S015 round
 * 2026-10-22, BL-004 facts 17-18) — a separate file so the monitor stays under the
 * monolith guard and both rules stay JVM-testable.
 *
 * D — the cache is an ACCESS-ORDER map: a put or read refreshes recency, so eviction
 * past [MAX_TRACKS] always drops the least recently used OTHER track. The previous
 * HashMap dropped the first ITERATOR key — arbitrary order — which ate the CURRENT
 * track's entry once the map filled: every tick re-ran the full first-sight path
 * (diagnostics dump, donor, jpeg re-encode) for the whole drive, ~4 log lines/s.
 *
 * E — the AVRCP pre-announcement (fact 18): ~1 s before a track change the session
 * announces the NEXT track with album and duration STRIPPED. Publishing it cost a
 * second uuid — a double push per track change. Such a read is now a NON-EVENT: no
 * publish, no hold tick, no clear — the card of the full variant keeps standing until
 * the full metadata arrives a second later. A track that never sends album AND
 * duration gets no card at all (accepted tradeoff: the planner's sources send both).
 */
internal object TrackSnapshotPolicy {

    /** Recent tracks only; the file cache rotates its own 24 files. */
    internal const val MAX_TRACKS = 8

    /** Access-order cache: the just-stored key becomes the newest, so it can never be
     *  its own eviction victim. */
    internal fun <V> newSnapshotCache(): MutableMap<String, V> =
        object : LinkedHashMap<String, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>): Boolean =
                size > MAX_TRACKS
        }

    /** The stripped pre-announcement signature (round 2026-10-22: ALBUM=- DURATION=-
     *  NUM_TRACKS=n<queue position> for ~1 s before the full metadata). */
    internal fun isDegradedPreAnnouncement(album: String?, durationMs: Long): Boolean =
        album.isNullOrEmpty() && durationMs <= 0L
}
