package com.geely.mockcar

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * A fake "phone playing over the car's Bluetooth" source (F014): a local MediaSession whose
 * metadata carries NO cover (the observed behavior of the head unit's Bluetooth app) plus a
 * media notification whose largeIcon artwork can arrive LATE — the exact rung the headunit
 * fork's fallback mines (round 9, конспект §2d). Buttons and adb actions drive
 * play/pause/stop and the late arrival so the whole fallback chain is rehearsed on an
 * emulator without a phone.
 */
object FakeBtSource {

    private const val NOTIFICATION_ID = 2
    private const val CHANNEL_ID = "mockcar"

    private const val CONTROLS: Long = PlaybackState.ACTION_PLAY or
        PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_SKIP_TO_NEXT or
        PlaybackState.ACTION_SKIP_TO_PREVIOUS

    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var title = ""

    // MediaSession has setPlaybackState but no getter for it (the controller side reads the
    // state), so the position/playing bookkeeping lives here.
    private var positionMs = 0L
    private var playing = false

    val active: Boolean get() = session != null
    val currentTitle: String get() = title

    /** Starts playing. The notification posts immediately WITHOUT artwork; when
     *  [artworkDelayMs] >= 0, the cover reposts that many milliseconds later. */
    fun play(context: Context, trackTitle: String, artist: String, artworkDelayMs: Long) {
        stop(context)
        title = trackTitle
        positionMs = 0L
        playing = true
        val s = MediaSession(context, "mockcar-bt")
        s.setPlaybackState(playingState())
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, trackTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Mock Album")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 200_000L)
                .build()
        )
        s.isActive = true
        session = s
        post(context, withArtwork = false)
        MockLog.i("fake-bt: playing \"$trackTitle\" (artwork in ${artworkDelayMs} ms)")
        if (artworkDelayMs >= 0) {
            main.postDelayed({
                if (session != null) {
                    post(context, withArtwork = true)
                    MockLog.i("fake-bt: late artwork posted")
                }
            }, artworkDelayMs)
        }
    }

    fun pause(context: Context) {
        val s = session ?: return
        if (!playing) return
        playing = false
        s.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, positionMs, 0f)
                .setActions(CONTROLS)
                .build()
        )
        MockLog.i("fake-bt: paused")
    }

    fun stop(context: Context) {
        main.removeCallbacksAndMessages(null)
        session?.release()
        session = null
        title = ""
        playing = false
        positionMs = 0L
        notificationManager(context).cancel(NOTIFICATION_ID)
        MockLog.i("fake-bt: stopped")
    }

    private fun playingState(): PlaybackState = PlaybackState.Builder()
        .setState(PlaybackState.STATE_PLAYING, positionMs, 1f)
        .setActions(CONTROLS)
        .build()

    /** The media notification: title/artist match the session (the fallback matches a cover
     *  against BOTH), the largeIcon is present only in the "late" repost. */
    private fun post(context: Context, withArtwork: Boolean) {
        val manager = notificationManager(context)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(CHANNEL_ID, "Mock car service", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = notification(context)
            .setContentTitle(title)
            .setContentText("mock bluetooth media")
            .setOngoing(false)
        if (withArtwork) builder.setLargeIcon(artwork())
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun notification(context: Context): Notification.Builder =
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

    private fun notificationManager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Deterministic two-tone cover with a caption — decodable, no binary asset in git. */
    private fun artwork(): Bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also { bmp ->
        val canvas = Canvas(bmp)
        canvas.drawRect(0f, 0f, 256f, 128f, Paint().apply { color = 0xFF335599.toInt() })
        canvas.drawRect(0f, 128f, 256f, 256f, Paint().apply { color = 0xFF995533.toInt() })
        canvas.drawText("MOCK", 128f, 142f, Paint().apply {
            color = Color.WHITE
            textSize = 48f
            textAlign = Paint.Align.CENTER
        })
    }
}
