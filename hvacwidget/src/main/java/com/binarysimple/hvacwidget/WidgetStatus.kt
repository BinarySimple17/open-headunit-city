package com.binarysimple.hvacwidget

/**
 * The last known status snapshot, keyed by status id (`null` = no answer / unknown).
 * The poller replaces it wholesale; taps overwrite their own key optimistically and the
 * poller confirms or corrects. In-memory only — the first render after boot shows the
 * unknown state until the first poll lands.
 */
object WidgetStatus {

    @Volatile
    var snapshot: Map<String, Int?> = emptyMap()
        private set

    /** Whole-snapshot replace (poll diff passed or the first read landed). */
    fun replace(next: Map<String, Int?>) {
        synchronized(this) { snapshot = LinkedHashMap(next) }
    }

    /** Optimistic single-key overwrite after an accepted write. */
    fun overwrite(id: String, value: Int) {
        synchronized(this) { snapshot = LinkedHashMap(snapshot).apply { put(id, value) } }
    }
}
