package com.andrerinas.openheadunit.geely.cluster

import android.content.Context
import android.content.SharedPreferences
import com.andrerinas.openheadunit.geely.hvac.HvacLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The Bluetooth track card on the instrument cluster (F013). Planner decision 2026-10-18
 * (car round 9): the AA-metadata card is retired — the only publisher is the head unit's
 * own Bluetooth session ([BtMediaMonitor]). That session mirrors whatever the phone plays
 * through the car — plain A2DP and Android Auto alike (round 9: while AA projected, the
 * AVRCP session carried the very same track) — and one publisher means one card with no
 * arbitration: round 9 caught our AA card (with its cover) being displaced by our own
 * Bluetooth card one second after publication. The AA stream's art bytes arrive as a DONOR
 * ([AaCoverDonor], D-011): they feed the card's last ladder rung and never publish. The
 * gate is our two switches plus the
 * notification listener ([BtMediaListener]) that both the session read and the artwork
 * rungs need; flipping any of them off stops the publication immediately and clears the
 * card — it must not hang around stale until the next track.
 *
 * Everything runs on a private single-thread executor: monitor ticks arrive from the main
 * thread, and the publish path (reflective binder calls, artwork file IO) must never sit
 * on it. A same-card re-publish collapses (the cluster pulls getters lazily — the
 * reference re-pushes only on real changes and ticks the position separately).
 */
class ClusterMediaBridge(context: Context) {

    private val app = context.applicationContext
    private val settings = ClusterMediaSettings(app)
    private val cache = ClusterArtworkCache(app)

    /** AA-stream art bytes for the Bluetooth card's ladder — a donor, never a publisher. */
    private val aaDonor = AaCoverDonor()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cluster-media").apply { isDaemon = true }
    }

    /** Worker-thread state: the card the cluster currently sees; null = nothing published. */
    private var lastSnapshot: TrackSnapshot? = null

    /** Worker-thread state: the Bluetooth session owns the card. */
    private var btOwner = false
    private var btMonitor: BtMediaMonitor? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        worker.execute { handleGateChanged() }
    }

    /** S015 source-probe lines come from the firmware's binder threads: marshal them onto
     *  the worker (the log write must never run on the binder side). */
    private val sourceEventSink: (String) -> Unit = { line -> worker.execute { HvacLog.i(line) } }

    init {
        settings.addListener(prefListener)
    }

    /** AA metadata carries album art: remember it under the track key (model A donor). */
    fun onAaCover(title: String, artist: String, bytes: ByteArray?) {
        worker.execute { aaDonor.offer(title, artist, bytes) }
    }

    /** The notification listener connected or died — the Bluetooth card may arm/disarm. */
    fun btAccessChanged() {
        worker.execute { handleGateChanged() }
    }

    /** Projection ended or the service is going down: a stray card must not outlive it. */
    fun onProjectionStopped() {
        worker.execute { handleClear("projection stopped") }
    }

    /** One read of the head unit's Bluetooth session ([BtMediaMonitor] tick). */
    fun onBtUpdate(snapshot: TrackSnapshot?, positionMs: Long, isPlaying: Boolean, sourcePackage: String) {
        worker.execute { handleBtUpdate(snapshot, positionMs, isPlaying, sourcePackage) }
    }

    private fun handleClear(reason: String) {
        // The Bluetooth card is not a projection artifact: with the phone playing over A2DP it
        // outlives the projection, exactly like the stock Bluetooth card it blends into.
        if (btOwner) {
            HvacLog.i("cluster: projection stopped; the bluetooth card stays")
            return
        }
        clearIfPublished(reason)
    }

    private fun handleGateChanged() {
        syncBtMonitor()
        if (!btFallbackArmed()) clearIfPublished("switch off")
    }

    /** The head unit's Bluetooth session is the single data source: while it plays, its
     *  content — the stock card's own data, plus any artwork the rungs find — owns the card. */
    private fun handleBtUpdate(
        snapshot: TrackSnapshot?,
        positionMs: Long,
        isPlaying: Boolean,
        sourcePackage: String,
    ) {
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
            // artwork verdict is the round-9 open question: on the real head unit the AVRCP
            // session carried no cover and no notification rung fired either. The package is
            // printed because the session choice (firstOrNull PLAYING, S015 fact 9) was
            // invisible in the log — planner asked to see WHICH session wins.
            HvacLog.i("cluster.bt: bluetooth session owns the card ($sourcePackage: " +
                "${snapshot.title}, artwork=${snapshot.artworkUri != null})")
        }
        if (snapshot != lastSnapshot) {
            if (publish(snapshot)) lastSnapshot = snapshot
        }
        DimMediaChannel.publishProgress(positionMs)
        // S015 diagnostics: which card does the cluster hold RIGHT NOW (uuid/sourceType)?
        // Read-back only — the publication model stays frozen this session (Task 2).
        if (DimMediaChannel.firmwareChannel) {
            DimMediaChannel.firmwareMediaInteraction()?.let { DimSourceProbe.readBack(it) }
        }
    }

    private fun releaseBt(reason: String) {
        if (!btOwner) return
        btOwner = false
        clearIfPublished(reason)
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
            val created = BtMediaMonitor(app, app.packageName, cache, aaDonor::coverFor) { s, p, playing, pkg ->
                onBtUpdate(s, p, playing, pkg)
            }
            btMonitor = created
            created.start()
        } else if (!want && monitor != null) {
            HvacLog.i("cluster.bt: fallback disarmed")
            monitor.stop()
            btMonitor = null
            DimSourceProbe.release()
            releaseBt("fallback disarmed")
        }
    }

    private fun publish(snapshot: TrackSnapshot): Boolean {
        if (!DimMediaChannel.available && !DimMediaChannel.connect(app)) return false
        val infoInterface = DimMediaChannel.playbackInfoInterface() ?: return false
        // The source probe arms itself after the first successful connect (once per
        // channel; the bridge worker thread is the caller — publish's own thread).
        if (DimMediaChannel.firmwareChannel) {
            DimMediaChannel.firmwareMediaInteraction()?.let { DimSourceProbe.armOnce(it, sourceEventSink) }
        }
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
}
