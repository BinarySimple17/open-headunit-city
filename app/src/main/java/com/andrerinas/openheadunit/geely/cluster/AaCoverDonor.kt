package com.andrerinas.openheadunit.geely.cluster

/**
 * The AA cover donor (S014, model A / D-011): the DIM publisher stays single — the
 * Bluetooth card (D-010) — but the album-art bytes the AA stream already delivers into
 * this process may FEED that card. The donor remembers the latest bytes offered per
 * normalized track key; [BtMediaMonitor]'s ladder reads through it as its last rung.
 * It never publishes a card and never touches the slot — a mismatch answers "no cover",
 * never a foreign cover.
 */
internal class AaCoverDonor {

    @Synchronized
    fun offer(title: String, artist: String, bytes: ByteArray?) {
        val key = normalizeKey(title, artist) ?: return
        if (bytes == null || bytes.isEmpty()) return
        synchronizedEntries[key] = bytes
    }

    @Synchronized
    fun coverFor(title: String?, artist: String?): ByteArray? {
        val key = normalizeKey(title ?: "", artist ?: "") ?: return null
        return synchronizedEntries[key]?.let { it.copyOf() }
    }

    /** Access-order map: the oldest track's bytes drop out past [MAX_TRACKS]. */
    private val synchronizedEntries = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
            size > MAX_TRACKS
    }

    internal companion object {
        private const val MAX_TRACKS = 8

        /** AVRCP and AA spell the same track slightly differently; trim + lowercase +
         *  collapsed whitespace. A track without a title has no identity (video media). */
        internal fun normalizeKey(title: String, artist: String): String? {
            val t = title.normalize()
            if (t.isEmpty()) return null
            return "$t|${artist.normalize()}"
        }

        private fun String.normalize(): String =
            trim().lowercase().replace(Regex("\\s+"), " ")
    }
}
