package com.andrerinas.openheadunit.geely.cluster

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
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
 * ([BtMediaListener]); the artwork comes from the session bitmaps, the art URIs, the
 * session's media notification, or — last rung (D-011) — the AA stream's own bytes for the
 * very same track. Only PLAYING sessions win; short dropouts are held through
 * ([SESSION_GRACE_MS], as the references do) so a jitter never flips the card, and the long
 * PLAYING⇄STOPPED pauses this unit produces (round 2026-10-21 — the slow half of the
 * flicker) hold the last card through ([CARD_HOLD_MS], S015 candidate A1) instead of
 * clearing at the grace's end; only a stop older than the hold reports null.
 */
class BtMediaMonitor(
    context: Context,
    private val selfPackage: String,
    private val cache: ClusterArtworkCache,
    private val aaCover: (title: String?, artist: String?) -> ByteArray?,
    private val onUpdate: (
        snapshot: TrackSnapshot?,
        positionMs: Long,
        isPlaying: Boolean,
        sourcePackage: String,
    ) -> Unit,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val snapshots = HashMap<String, TrackSnapshot>()

    private var stopped = true
    private var lastKey: String? = null
    private val holdPolicy = CardHoldPolicy(SESSION_GRACE_MS, CARD_HOLD_MS)
    private var lastOverview: String? = null

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
        holdPolicy.reset()
        lastOverview = null
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
        val overview = controllers.joinToString(" ") { "${it.packageName}:${it.playbackState?.state}" }
        if (overview != lastOverview) {
            lastOverview = overview
            HvacLog.d("cluster.bt: sessions: $overview")
        }
        val meta = controller?.metadata
        if (controller == null || meta == null) {
            missingRead()
            return
        }
        val state = controller.playbackState
        val key = trackKeyOf(meta)
        if (key != lastKey) {
            // Once per track: the full surface the public API exposes plus the direct AVRCP
            // stack probe (round-10 cover quest — the firmware may hold art it never
            // publishes into the session).
            AvrcpArtProbe.ensureBound(app)
            BtSessionDiagnostics.describeBundle("session extras", runCatching { controller.extras }.getOrNull())
            BtSessionDiagnostics.describeBundle("state extras", runCatching { state?.extras }.getOrNull())
            BtSessionDiagnostics.describeQueue(controller)
            AvrcpArtProbe.dump()
        }
        val snapshot = snapshotFor(meta, key, controller.packageName ?: "")
        val position = state?.let {
            it.position + if (it.state == PlaybackState.STATE_PLAYING) {
                SystemClock.elapsedRealtime() - it.lastPositionUpdateTime
            } else 0L
        } ?: 0L
        if (snapshot == null) {
            // A PLAYING session whose bundle carries no title/artist/album (fact 9: junk
            // sessions win the race) is no read at all — it reaches the same hold as a dead
            // session instead of clearing instantly past it (beta22 review).
            missingRead()
            return
        }
        holdPolicy.validTick(SystemClock.elapsedRealtime())
        onUpdate(snapshot, position, true, controller.packageName ?: "")
    }

    /** Every read without a playable snapshot goes through ONE hold decision (beta22
     *  review): clearing on the first bad tick cost a flip pair at 17:33 (round 8), and
     *  this unit's pauses run 10-90 s (round 2026-10-21) — so short dropouts stay silent
     *  (grace), long ones hold the card as published (position frozen, nothing republished),
     *  and only a hold run out since the last valid read clears as before. */
    private fun missingRead() {
        if (holdPolicy.missingTick(SystemClock.elapsedRealtime()) == CardHoldPolicy.Decision.HOLD) return
        lastKey = null
        onUpdate(null, 0L, false, "")
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
            HvacLog.d("cluster.bt: session keys ($title): ${BtSessionDiagnostics.describeKeys(meta)}")
            val desc = runCatching { meta.description }.getOrNull()
            if (desc != null) {
                val descExtras = desc.extras?.keySet()?.sorted()?.joinToString(" ").orEmpty()
                HvacLog.d("cluster.bt: session description: iconUri=${desc.iconUri ?: "none"}, " +
                    "iconBitmap=${if (desc.iconBitmap != null) "yes" else "no"}, extras=[$descExtras]")
            }
        }
        val artwork = artworkOf(meta, pkg, title, artist)
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

    /** Session artwork ladder — the first rung with bytes wins, its verdict lands in the
     *  car log so "no cover anywhere" and "the cover was not rendered" never blur:
     *  the AVRCP bitmaps (album art, art, display icon, the description's bitmap — the raw
     *  DISPLAY_ICON_BITMAP key is hidden API), the art URIs (album-art content provider),
     *  the public icon URIs (display-icon uri, description uri), the session's media
     *  notification (the rung Button 2.8.3 mines; silent on this head unit — 2026-10-18),
     *  and last the AA-stream DONOR (D-011): the same track's bytes the projection already
     *  delivered into this process. */
    private fun artworkOf(meta: MediaMetadata, pkg: String, title: String, artist: String): ByteArray? {
        val bitmap = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: meta.description?.iconBitmap
        jpegOf(bitmap)?.let { return rung("session bitmap", title, it) }
        for (key in listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI)) {
            val uri = meta.getString(key)
            if (uri.isNullOrEmpty()) continue
            runCatching {
                app.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                    return rung("art uri", title, stream.readBytes())
                }
            }
        }
        for (uri in listOf(
            meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
            meta.description?.iconUri?.toString(),
        )) {
            if (uri.isNullOrEmpty()) continue
            runCatching {
                app.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                    return rung("icon uri", title, stream.readBytes())
                }
            }
        }
        if (pkg.isNotEmpty()) jpegOf(BtMediaListener.artworkFor(pkg, title))?.let {
            return rung("notification", title, it)
        }
        // The donor's bytes are already an encoded image (the AA stream's albumArt) —
        // no re-encode, store as they came.
        aaCover(title, artist)?.takeIf { it.isNotEmpty() }?.let {
            HvacLog.i("cluster.bt: cover from the AA stream ($title)")
            return it
        }
        return null
    }

    private fun rung(name: String, title: String, bytes: ByteArray): ByteArray {
        HvacLog.i("cluster.bt: cover rung: $name ($title)")
        return bytes
    }

    private fun jpegOf(bitmap: Bitmap?): ByteArray? {
        if (bitmap == null) return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun firstOf(meta: MediaMetadata, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> meta.getString(key)?.takeIf { it.isNotEmpty() } } ?: ""

    internal companion object {
        internal const val TICK_MS = 1000L
        internal const val JPEG_QUALITY = 90
        private const val MAX_TRACKS = 8

        /** Short AVRCP dropouts are held through instead of releasing the card (round 9). */
        internal const val SESSION_GRACE_MS = 2000L

        /** The round-2026-10-21 pauses run far beyond the grace (10-90 s), and each clear
         *  flashed the stock Bluetooth card: the last card is held for this long since the
         *  last valid read (planner window 30-60 s, S015 candidate A1), then clears. */
        internal const val CARD_HOLD_MS = 60_000L

        /** Title|artist|album — its sha256 doubles as the cover file name and the card uuid,
         *  so the same track keeps its identity across re-reads. DURATION is deliberately
         *  NOT part of the key: this unit jitters a numeric field every tick (BL-004 fact
         *  10), which re-issued uuid and duplicated donor+jpeg work at 1 Hz. Stale cover
         *  files keyed with the old duration survive until the cache rotates them away. */
        internal fun trackKeyOf(meta: MediaMetadata): String = trackKeyOf(
            title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
            artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
            album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
        )

        /** Pure core of [trackKeyOf] — JVM-testable. */
        internal fun trackKeyOf(title: String, artist: String, album: String): String =
            listOf(title, artist, album).joinToString("|")
    }
}
