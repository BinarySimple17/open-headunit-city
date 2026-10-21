package com.andrerinas.openheadunit.geely.cluster

import android.media.MediaMetadata
import android.media.session.MediaController
import android.os.Bundle
import com.andrerinas.openheadunit.geely.hvac.HvacLog

/**
 * The car-log diagnostics of the Bluetooth session (the round-10 cover hunt, adb on the
 * unit being out of the planner's reach): one compact line per aspect, once per track
 * change — every metadata key with its value kind, both extras bundles, the now-playing
 * queue with its icons. The log alone must answer "does the session carry ANY art" without
 * a shell; a line missing is itself a verdict.
 */
internal object BtSessionDiagnostics {

    /** Every metadata key with a compact value kind: b=WxH bitmap, s=len string, n=value
     *  for numbers (printed since the line-showing round 2026-10-21 — a numeric field the
     *  unit jitters every tick proved the cleanup case; only kinds left it invisible),
     *  -=none of those. The cover quest needs the FULL key list, not just the rungs we
     *  read; [AvrcpArtProbe] describes the stack's own metadata with the same helper. */
    internal fun describeKeys(meta: MediaMetadata): String = runCatching {
        val parts = meta.keySet().sorted().map { key ->
            val kind = meta.getBitmap(key)?.let { "b${it.width}x${it.height}" }
                ?: meta.getString(key)?.takeIf { it.isNotEmpty() }?.let { "s${it.length}" }
                ?: meta.getLong(key).takeIf { it != 0L }?.let { "n$it" }
                ?: "-"
            "$key=$kind"
        }
        if (parts.isEmpty()) "(none)" else parts.joinToString(" ")
    }.getOrDefault("(unreadable)")

    /** A bundle's keys with short values — the controller and the playbackState carry two
     *  DIFFERENT bundles, and some stacks surface AVRCP-side data in one of them only. */
    internal fun describeBundle(label: String, extras: Bundle?) {
        if (extras == null) return
        val line = runCatching {
            extras.keySet().sorted().joinToString(" ") { key ->
                val value = when (val v = extras.get(key)) {
                    is String -> "s${v.length}:${v.take(40)}"
                    is CharSequence -> "s${v.length}"
                    is Number -> "n"
                    else -> "-"
                }
                "$key=$value"
            }
        }.getOrNull().orEmpty()
        if (line.isNotEmpty()) HvacLog.d("cluster.bt: $label: $line")
    }

    /** The session's now-playing queue — queue items carry their own MediaDescription, and
     *  some stacks expose per-track art (uri or bitmap) only there. */
    internal fun describeQueue(controller: MediaController) {
        runCatching {
            val items = controller.queue.orEmpty()
            if (items.isEmpty()) {
                HvacLog.d("cluster.bt: session queue: empty")
                return
            }
            val withIcon = items.count {
                it.description?.iconUri != null || it.description?.iconBitmap != null
            }
            val first = items.first().description
            val firstIcon = first?.iconUri?.toString()
                ?: if (first?.iconBitmap != null) "bitmap" else "none"
            HvacLog.d("cluster.bt: session queue: ${items.size} items, " +
                "icons=$withIcon/${items.size}, first=$firstIcon")
        }.onFailure { HvacLog.d("cluster.bt: session queue: unreadable (${it.message})") }
    }
}
