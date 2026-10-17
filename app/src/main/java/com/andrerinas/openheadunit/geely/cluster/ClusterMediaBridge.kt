package com.andrerinas.openheadunit.geely.cluster

import android.content.Context
import android.content.SharedPreferences
import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback
import com.andrerinas.openheadunit.geely.hvac.HvacLog
import com.andrerinas.openheadunit.utils.Settings
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The AA-metadata → instrument-cluster redirect (F013). Lives ON TOP of the native
 * "AA now playing on media session" switch: AapService hands us the very same events that
 * drive the system media session, and we additionally publish them to the cluster card
 * through [DimMediaChannel]. The gate is strict ([ClusterMediaGate]): only when BOTH
 * switches are on does anything leave the app; flipping either off stops the publication
 * immediately and clears the card — it must not hang around stale until the next track.
 *
 * Everything runs on a private single-thread executor: AA callbacks arrive on transport
 * threads, and the publish path (reflective binder calls, artwork file IO) must never sit
 * on them. A paused track keeps its card with only the status flag flipped; a same-card
 * re-publish collapses (the cluster pulls getters lazily — the reference re-pushes only on
 * real changes and ticks the position separately).
 */
class ClusterMediaBridge(context: Context) {

    private val app = context.applicationContext
    private val settings = ClusterMediaSettings(app)
    private val cache = ClusterArtworkCache(app)
    private val nativePrefs: SharedPreferences =
        app.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cluster-media").apply { isDaemon = true }
    }
    private val publishSeq = AtomicLong()

    /** Worker-thread state: the card the cluster currently sees; null = nothing published. */
    private var lastSnapshot: TrackSnapshot? = null

    /** Worker-thread state: the latest AA content, even while Bluetooth owns the card. */
    private var aaContent: TrackSnapshot? = null

    /** Worker-thread state: the Bluetooth session owns the card (the reference's BT trick). */
    private var btOwner = false
    private var btMonitor: BtMediaMonitor? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        worker.execute { handleGateChanged() }
    }

    init {
        settings.addListener(prefListener)
        nativePrefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    /** New track metadata from the phone — the same event the media session gets. */
    fun onAaMetadata(meta: MediaPlayback.MediaMetaData) {
        val seq = publishSeq.incrementAndGet()
        worker.execute { handlePublish(meta, seq) }
    }

    /** Play/pause + position tick from the phone. */
    fun onAaPlaybackState(isPlaying: Boolean, positionMs: Long) {
        worker.execute { handleProgress(isPlaying, positionMs) }
    }

    /** Projection ended or the service is going down: the card must not outlive it. */
    fun onProjectionStopped() {
        worker.execute { handleClear("projection stopped") }
    }

    /** The notification listener connected or died — the Bluetooth fallback may arm/disarm. */
    fun btAccessChanged() {
        worker.execute { handleGateChanged() }
    }

    /** One read of the head unit's Bluetooth session ([BtMediaMonitor] tick). */
    fun onBtUpdate(snapshot: TrackSnapshot?, positionMs: Long, isPlaying: Boolean) {
        worker.execute { handleBtUpdate(snapshot, positionMs, isPlaying) }
    }

    private fun handlePublish(meta: MediaPlayback.MediaMetaData, seq: Long) {
        if (!gateOpen()) {
            clearIfPublished("gate closed")
            return
        }
        val artwork = artworkFor(meta, trackKey = trackKeyOf(meta))
        val snapshot = snapshotFrom(meta, aaContent?.isPlaying ?: false, artwork)
        // A newer metadata event arrived while we were writing the cover — it supersedes us.
        if (seq != publishSeq.get()) return
        aaContent = snapshot
        // While the Bluetooth session owns the card its content waits; it takes over on release.
        if (btOwner) return
        if (snapshot == lastSnapshot) return
        if (publish(snapshot)) lastSnapshot = snapshot
    }

    private fun handleProgress(isPlaying: Boolean, positionMs: Long) {
        if (!gateOpen()) {
            clearIfPublished("gate closed")
            return
        }
        if (btOwner) return
        val snapshot = aaContent ?: return
        if (isPlaying != snapshot.isPlaying) {
            val updated = snapshot.copy(isPlaying = isPlaying)
            // Card content changed — the cluster only re-pulls after a fresh push.
            if (!publish(updated)) return
            aaContent = updated
            lastSnapshot = updated
        }
        DimMediaChannel.publishProgress(positionMs)
    }

    private fun handleClear(reason: String) {
        // The Bluetooth card is not a projection artifact: with the phone playing over A2DP it
        // outlives the projection, exactly like the stock Bluetooth card it blends into.
        if (btOwner) {
            HvacLog.i("cluster: projection stopped; the bluetooth card stays")
            return
        }
        clearIfPublished(reason)
        DimMediaChannel.publishProgress(0L)
    }

    private fun handleGateChanged() {
        syncBtMonitor()
        if (gateOpen()) {
            // Nothing: the card reappears with the next tick/metadata — publishing a stale
            // snapshot here would resurrect a card the gate had just cleared.
            return
        }
        releaseBt("gate closed")
        val reason = when {
            !nativePrefs.getBoolean(Settings.KEY_SYNC_MEDIA_SESSION_AA_METADATA, false) ->
                "native media-session switch off"
            else -> "our switch off"
        }
        clearIfPublished(reason)
    }

    /** Bluetooth fallback state: while a phone session plays through the car's A2DP channel,
     *  its content — the stock card's own data, plus artwork — owns the card; AA content waits. */
    private fun handleBtUpdate(snapshot: TrackSnapshot?, positionMs: Long, isPlaying: Boolean) {
        if (!btFallbackArmed()) {
            releaseBt("fallback disarmed")
            return
        }
        if (snapshot == null || !isPlaying) {
            releaseBt("bluetooth session stopped")
            return
        }
        if (!btOwner) {
            btOwner = true
            // artwork verdict is the round-8 open question: does the car's AVRCP session even
            // carry a cover (silent null looks identical to a rejected render in the log).
            HvacLog.i("cluster.bt: bluetooth session owns the card " +
                "(${snapshot.title}, artwork=${snapshot.artworkUri != null})")
        }
        if (snapshot != lastSnapshot) {
            if (publish(snapshot)) lastSnapshot = snapshot
        }
        DimMediaChannel.publishProgress(positionMs)
    }

    private fun releaseBt(reason: String) {
        if (!btOwner) return
        btOwner = false
        val aa = aaContent
        if (aa != null && gateOpen()) {
            HvacLog.i("cluster.bt: card returned to AA content ($reason)")
            if (publish(aa)) lastSnapshot = aa
        } else {
            HvacLog.i("cluster.bt: card cleared ($reason)")
            clearIfPublished(reason)
        }
    }

    private fun btFallbackArmed(): Boolean =
        settings.enabled && settings.btFallback && BtMediaListener.connected

    private fun syncBtMonitor() {
        val want = settings.enabled && settings.btFallback
        val monitor = btMonitor
        if (want && monitor == null) {
            if (!BtMediaListener.connected) {
                HvacLog.w("cluster.bt: fallback armed but notification access not granted")
                return
            }
            HvacLog.i("cluster.bt: fallback armed (notification listener connected)")
            val created = BtMediaMonitor(app, app.packageName) { s, p, playing ->
                onBtUpdate(s, p, playing)
            }
            btMonitor = created
            created.start()
        } else if (!want && monitor != null) {
            HvacLog.i("cluster.bt: fallback disarmed")
            monitor.stop()
            btMonitor = null
            releaseBt("fallback disarmed")
        }
    }

    private fun gateOpen(): Boolean = ClusterMediaGate.isOpen(
        nativeSync = nativePrefs.getBoolean(Settings.KEY_SYNC_MEDIA_SESSION_AA_METADATA, false),
        ours = settings.enabled,
    )

    private fun artworkFor(meta: MediaPlayback.MediaMetaData, trackKey: String): String? {
        if (!(meta.hasAlbumArt() && !meta.albumArt.isEmpty)) {
            // Round 6 follow-up: "no image on the card" must be distinguishable in the car
            // log — without this line a silent null and a failed store look the same.
            HvacLog.d("cluster.art: metadata without album art (${meta.song})")
            return null
        }
        val uri = cache.store(trackKey, meta.albumArt.toByteArray())
        if (uri == null) HvacLog.w("cluster.art: cover NOT stored (${meta.albumArt.size()} B)")
        else HvacLog.d("cluster.art: cover stored: $uri")
        return uri
    }

    private fun publish(snapshot: TrackSnapshot): Boolean {
        if (!DimMediaChannel.available && !DimMediaChannel.connect(app)) return false
        val infoInterface = DimMediaChannel.playbackInfoInterface() ?: return false
        return DimMediaChannel.publish(PlaybackInfoProxy.create(infoInterface, snapshot))
    }

    private fun clearIfPublished(reason: String) {
        if (lastSnapshot == null) return
        lastSnapshot = null
        HvacLog.i("cluster: card cleared ($reason)")
        // The empty implementation is the reference's own way of wiping the card.
        val infoInterface = DimMediaChannel.playbackInfoInterface() ?: return
        DimMediaChannel.publish(PlaybackInfoProxy.create(infoInterface, TrackSnapshot.EMPTY))
    }

    internal companion object {

        /** Stable per-track key: the cover file name derives from it, dedup compares cards. */
        internal fun trackKeyOf(meta: MediaPlayback.MediaMetaData): String =
            listOf(meta.song, meta.artist, meta.album, durationOf(meta).toString())
                .joinToString("|")

        /** AA carries duration in whole seconds; absent means unknown (-1, reference marker). */
        internal fun durationOf(meta: MediaPlayback.MediaMetaData): Long =
            if (meta.hasDurationSeconds()) meta.durationSeconds * 1000L else -1L

        internal fun snapshotFrom(
            meta: MediaPlayback.MediaMetaData,
            isPlaying: Boolean,
            artworkUri: String?,
        ): TrackSnapshot = TrackSnapshot(
            title = meta.song.takeIf { it.isNotEmpty() },
            artist = meta.artist.takeIf { it.isNotEmpty() },
            album = meta.album.takeIf { it.isNotEmpty() },
            artworkUri = artworkUri,
            durationMs = durationOf(meta),
            isPlaying = isPlaying,
            // No track, no identity: an all-empty metadata hashes to nothing.
            trackUuid = if (meta.song.isEmpty() && meta.artist.isEmpty() && meta.album.isEmpty()) null
            else ClusterArtworkCache.sha256Hex(trackKeyOf(meta)),
        )
    }
}
