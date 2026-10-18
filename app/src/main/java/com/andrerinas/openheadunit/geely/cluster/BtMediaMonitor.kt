package com.andrerinas.openheadunit.geely.cluster

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.andrerinas.openheadunit.geely.hvac.HvacLog
import java.io.ByteArrayOutputStream

/**
 * Reads the head unit's own media sessions (F013 Bluetooth fallback — конспект §2c): when the
 * phone plays through the car's A2DP/AVRCP channel, the car's Bluetooth app holds a local
 * session with the very track the stock card shows, but without artwork. This monitor re-reads
 * that session once a second and hands [onUpdate] a snapshot built from ITS fields plus the
 * extracted artwork. Reads require this app to be the connected notification listener
 * ([BtMediaListener]); the artwork comes from the session bitmaps, the art URI, or the
 * session's media notification. Only PLAYING sessions win; short dropouts
 * are held through ([SESSION_GRACE_MS], as the references do) so a jitter never flips the
 * card, and a real stop reports null so the card clears.
 */
class BtMediaMonitor(
    context: Context,
    private val selfPackage: String,
    private val onUpdate: (snapshot: TrackSnapshot?, positionMs: Long, isPlaying: Boolean) -> Unit,
) {
    private val app = context.applicationContext
    private val cache = ClusterArtworkCache(app)
    private val main = Handler(Looper.getMainLooper())
    private val snapshots = HashMap<String, TrackSnapshot>()

    private var stopped = true
    private var lastKey: String? = null
    private var lastPlayingAt = 0L
    private var gapHeld = false

    private val tick: Runnable = Runnable {
        if (stopped) return@Runnable
        runCatching { tickOnce() }
            .onFailure { HvacLog.w("cluster.bt: session read failed: ${it.message}") }
        main.postDelayed(tick, TICK_MS)
    }

    fun start() {
        stopped = false
        main.post(tick)
    }

    fun stop() {
        stopped = true
        main.removeCallbacks(tick)
        snapshots.clear()
        lastKey = null
        lastPlayingAt = 0L
        gapHeld = false
    }

    private fun tickOnce() {
        if (!BtMediaListener.connected) return
        val manager = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return
        val controllers = runCatching {
            manager.getActiveSessions(ComponentName(app, BtMediaListener::class.java))
        }.getOrElse { return }
        val controller = controllers
            .filter { it.packageName != selfPackage }
            .firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val meta = controller?.metadata
        if (controller == null || meta == null) {
            // References hold the card through short AVRCP dropouts (Button's grace constants
            // 1800-2600 ms); releasing on the first bad tick made every jitter flip the card to
            // the other content and back — round 8 caught exactly such a flip pair at 17:33.
            if (lastPlayingAt > 0L &&
                SystemClock.elapsedRealtime() - lastPlayingAt < SESSION_GRACE_MS
            ) {
                if (!gapHeld) {
                    gapHeld = true
                    HvacLog.d("cluster.bt: short session gap — holding the card")
                }
                return
            }
            lastPlayingAt = 0L
            gapHeld = false
            lastKey = null
            onUpdate(null, 0L, false)
            return
        }
        lastPlayingAt = SystemClock.elapsedRealtime()
        gapHeld = false
        val state = controller.playbackState
        val key = trackKeyOf(meta)
        if (key != lastKey) describeExtras(controller)
        val snapshot = snapshotFor(meta, key, controller.packageName ?: "")
        val position = state?.let {
            it.position + if (it.state == PlaybackState.STATE_PLAYING) {
                SystemClock.elapsedRealtime() - it.lastPositionUpdateTime
            } else 0L
        } ?: 0L
        onUpdate(snapshot, position, snapshot != null)
    }

    /** Per-tick fast path: an unchanged track WITH its cover returns the stored snapshot.
     *  A coverless track is re-extracted every tick — the cover can reach the session's media
     *  notification seconds after the metadata (the rung Button 2.8.3 mines; not observed on
     *  this head unit's own Bluetooth session so far — car log 2026-10-18), and both working
     *  references retry for exactly that window; the refreshed snapshot then re-publishes the
     *  card mid-track. */
    private fun snapshotFor(meta: MediaMetadata, key: String, pkg: String): TrackSnapshot? {
        val known = snapshots[key]
        if (key == lastKey && known?.artworkUri != null) return known
        lastKey = key
        val firstSight = known == null
        val snapshot = buildSnapshot(meta, key, pkg, firstSight)
        if (snapshot != null) {
            if (known != null && known.artworkUri == null && snapshot.artworkUri != null) {
                HvacLog.i("cluster.bt: cover arrived late (${snapshot.title}) — card re-published")
            }
            snapshots[key] = snapshot
            val keys = snapshots.keys.iterator()
            if (snapshots.size > MAX_TRACKS && keys.hasNext()) {
                keys.next()
                keys.remove()
            }
        }
        return snapshot
    }

    private fun buildSnapshot(
        meta: MediaMetadata,
        key: String,
        pkg: String,
        firstSight: Boolean,
    ): TrackSnapshot? {
        val title = firstOf(meta, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE)
        val artist = firstOf(meta, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST)
        val album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (title.isEmpty() && artist.isEmpty() && album.isEmpty()) return null
        if (firstSight) {
            // The AVRCP cover quest (round 10): the car log must show EVERYTHING the session
            // actually carries — every key and its value kind — so "the firmware keeps the
            // cover somewhere else" becomes provable without adb on the unit. Once per track.
            HvacLog.d("cluster.bt: session keys ($title): ${describeKeys(meta)}")
        }
        val artwork = artworkOf(meta, pkg, title)
        if (artwork == null && firstSight) {
            // Runs once per track (retries stay silent until they succeed) — the car log
            // must separate "the session carries no cover" from "the cover was not rendered".
            HvacLog.d("cluster.bt: session metadata has no artwork ($title)")
        }
        val snapshot = TrackSnapshot(
            title = title.takeIf { it.isNotEmpty() },
            artist = artist.takeIf { it.isNotEmpty() },
            album = album.takeIf { it.isNotEmpty() },
            artworkUri = artwork?.let { cache.store(key, it) },
            durationMs = if (durationMs > 0) durationMs else -1L,
            isPlaying = true,
            trackUuid = ClusterArtworkCache.sha256Hex(key),
        )
        if (artwork != null && snapshot.artworkUri == null) {
            HvacLog.w("cluster.bt: artwork ${artwork.size} B NOT stored")
        }
        return snapshot
    }

    /** Session artwork ladder: the AVRCP bitmaps first (embedded art, then the display icon
     *  via the public description — the raw DISPLAY_ICON_BITMAP key is hidden API), then the
     *  art URI (album-art content provider), then the cover of the session's own media
     *  notification (the rung Button 2.8.3 mines; so far silent on this head unit — car log
     *  2026-10-18). */
    private fun artworkOf(meta: MediaMetadata, pkg: String, title: String): ByteArray? {
        val bitmap = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.description?.iconBitmap
        jpegOf(bitmap)?.let { return it }
        for (key in listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI)) {
            val uri = meta.getString(key)
            if (uri.isNullOrEmpty()) continue
            runCatching {
                app.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                    return stream.readBytes()
                }
            }
        }
        if (pkg.isNotEmpty()) jpegOf(BtMediaListener.artworkFor(pkg, title))?.let { return it }
        return null
    }

    private fun jpegOf(bitmap: Bitmap?): ByteArray? {
        if (bitmap == null) return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    /** Every metadata key with a compact value kind: b=WxH bitmap, s=len string, n=number,
     *  -=none of those. The cover quest needs the FULL key list, not just the rungs we read. */
    private fun describeKeys(meta: MediaMetadata): String = runCatching {
        val parts = meta.keySet().sorted().map { key ->
            val kind = meta.getBitmap(key)?.let { "b${it.width}x${it.height}" }
                ?: meta.getString(key)?.takeIf { it.isNotEmpty() }?.let { "s${it.length}" }
                ?: meta.getLong(key).takeIf { it != 0L }?.let { "n" }
                ?: "-"
            "$key=$kind"
        }
        if (parts.isEmpty()) "(none)" else parts.joinToString(" ")
    }.getOrDefault("(unreadable)")

    /** The controller's extras bundle — some stacks surface AVRCP-side data (folder data,
     *  cover-art handles) exactly there, never in the metadata. Keys plus short strings. */
    private fun describeExtras(controller: MediaController) {
        val extras = runCatching { controller.extras }.getOrNull() ?: return
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
        if (line.isNotEmpty()) HvacLog.d("cluster.bt: session extras: $line")
    }

    private fun firstOf(meta: MediaMetadata, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> meta.getString(key)?.takeIf { it.isNotEmpty() } } ?: ""

    internal companion object {
        internal const val TICK_MS = 1000L
        internal const val JPEG_QUALITY = 90
        private const val MAX_TRACKS = 8

        /** Short AVRCP dropouts are held through instead of releasing the card (round 9). */
        internal const val SESSION_GRACE_MS = 2000L

        /** Title|artist|album|duration — its sha256 doubles as the cover file name and the
         *  card uuid, so the same track keeps its identity across re-reads. */
        internal fun trackKeyOf(meta: MediaMetadata): String =
            listOf(
                meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
                meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
                meta.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
                meta.getLong(MediaMetadata.METADATA_KEY_DURATION).toString(),
            ).joinToString("|")
    }
}
