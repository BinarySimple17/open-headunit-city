package com.andrerinas.openheadunit.geely.cluster

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.geely.hvac.HvacLog

/**
 * The grant-holder of the Bluetooth side of the cluster card (F013): reading the head unit's
 * local media sessions (the car's own Bluetooth app holding the phone's AVRCP metadata)
 * requires this app to be the connected notification listener — exactly how the reference
 * does it (конспект §2c). The presence of the grant unlocks [BtMediaMonitor], which the user
 * arms with the "Bluetooth music" switch in the GEELY tab.
 *
 * Round 9: on this head unit the Bluetooth session's metadata carries no cover at all — the
 * cover travels in the Bluetooth app's media NOTIFICATION and lands there seconds after the
 * track starts. Both working references mine that notification (Button 2.8.3:
 * getLargeIcon → extras android.largeIcon → extras android.picture, cached against package
 * and title because the arrival is late). This listener caches the last artwork bitmap seen
 * in a foreign notification; the monitor retries artwork extraction while its track is still
 * coverless, so a late cover re-publishes the card mid-track.
 */
class BtMediaListener : NotificationListenerService() {

    @Volatile private var alive = false

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onListenerConnected() {
        alive = true
        sweepExisting()
        App.provide(this).clusterMediaBridge.btAccessChanged()
    }

    override fun onListenerDisconnected() {
        alive = false
        App.provide(this).clusterMediaBridge.btAccessChanged()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val artwork = artworkOf(n) ?: return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        cached = NotificationArtwork(pkg, title, artwork)
        HvacLog.d("cluster.bt: notification carries artwork ($pkg, ${artwork.width}x${artwork.height})")
    }

    /** A reconnect (or a start after the cover already posted) must not wait for the next
     *  repost: the current set is swept once. Media notifications repost on track change,
     *  which is exactly the event a fresh cover arrives with. */
    private fun sweepExisting() {
        runCatching { activeNotifications?.forEach { onNotificationPosted(it) } }
    }

    /** The reference ladder (Button MusicNotificationListener): the notification's large
     *  icon first, then the extras Bitmaps the media style puts there. NB: `getLargeIcon()`
     *  is called by name — the synthetic `n.largeIcon` would hit the deprecated Bitmap FIELD. */
    private fun artworkOf(n: Notification): Bitmap? {
        val drawable = runCatching {
            n.getLargeIcon()?.loadDrawable(applicationContext)
        }.getOrNull()
        bitmapOf(drawable)?.let { return it }
        n.extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)?.let { return it }
        n.extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)?.let { return it }
        return null
    }

    private fun bitmapOf(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        (drawable as? BitmapDrawable)?.bitmap?.let { return it }
        val w = drawable.intrinsicWidth
        val h = drawable.intrinsicHeight
        if (w <= 0 || h <= 0) return null
        return runCatching {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { out ->
                drawable.setBounds(0, 0, w, h)
                drawable.draw(Canvas(out))
            }
        }.getOrNull()
    }

    override fun onDestroy() {
        alive = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** The cover the last foreign notification carried, with what to match it against. */
    data class NotificationArtwork(val pkg: String, val title: String, val bitmap: Bitmap)

    companion object {
        @Volatile private var instance: BtMediaListener? = null
        @Volatile private var cached: NotificationArtwork? = null

        /** True while this app is the bound, connected notification listener. */
        val connected: Boolean
            get() = instance?.alive == true

        /** The monitor's last rung: the session has no cover, but its notification may.
         *  Matched by package and title so a stale cover never lands on a new track. */
        fun artworkFor(pkg: String, title: String): Bitmap? {
            val art = cached ?: return null
            if (art.pkg != pkg) return null
            if (title.isNotEmpty() && art.title.isNotEmpty() && art.title != title) return null
            return art.bitmap
        }
    }
}
