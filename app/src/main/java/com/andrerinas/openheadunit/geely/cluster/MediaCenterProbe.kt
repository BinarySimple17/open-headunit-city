package com.andrerinas.openheadunit.geely.cluster

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import com.andrerinas.openheadunit.geely.hvac.HvacLog
import java.io.File
import java.io.FileOutputStream

/**
 * The observation surface for the flicker hunt (BL-004 facts 20/25, S015 round 2026-10-23):
 * polls the vendor media center for what the STOCK Bluetooth feed holds right now — the
 * invisible competing publisher's own view (title, artist, cover, play state) — and logs
 * every change to the HVAC log. Read-only: nothing is ever written back.
 *
 * The path is the oneosapi binder chain, driven by RAW PARCELS — no oneosapi class is on
 * our classpath, but descriptors, transaction codes and the MediaData parcel layout are
 * pinned by the reference AIDL stubs (GInputBridge-main, com_geely):
 * bind com.geely.service.oneosapi/.OneOSApiService → IServiceManager.getService(3) →
 * IMediaCenter.getMusicManager() → IMusicManager.getCurrentPlayState(2)/getCurrentMediaData(2),
 * where source 2 = AudioSource.AUDIO_SOURCE_BT.ordinal(). A descriptor mismatch (firmware
 * drift) aborts before any foreign transaction code fires; every call is guarded — the
 * probe can log failures, never crash the app.
 */
object MediaCenterProbe {

    private const val SVC_PACKAGE = "com.geely.service.oneosapi"
    private const val SVC_CLASS = "com.geely.service.oneosapi.OneOSApiService"
    private const val TOKEN_SERVICE_MANAGER = "com.geely.lib.oneosapi.IServiceManager"
    private const val TOKEN_MEDIA_CENTER = "com.geely.lib.oneosapi.mediacenter.IMediaCenter"
    private const val TOKEN_MUSIC_MANAGER = "com.geely.lib.oneosapi.mediacenter.IMusicManager"
    private const val TX_GET_SERVICE = 2
    private const val MEDIA_CENTER_SERVICE_ID = 3
    private const val TX_GET_MUSIC_MANAGER = 5
    private const val TX_GET_CURRENT_PLAY_STATE = 15
    private const val TX_GET_CURRENT_MEDIA_DATA = 17

    /** com.geely.lib.oneosapi.mediacenter.constant.MediaCenterConstant.AudioSource.AUDIO_SOURCE_BT. */
    private const val BT_SOURCE = 2

    private const val POLL_MS = 5_000L
    private const val HEARTBEAT_POLLS = 12

    @Volatile private var running = false
    @Volatile private var appContext: Context? = null
    @Volatile private var serviceManager: IBinder? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var musicManager: IBinder? = null
    private var lastSignature: String? = null
    private var pollCount = 0
    private var failureLogged = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            handler?.post {
                serviceManager = binder
                musicManager = null
                if (binder != null) {
                    HvacLog.i("cluster.mcprobe: oneosapi bound (${SVC_PACKAGE})")
                    failureLogged = false
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handler?.post {
                serviceManager = null
                musicManager = null
                lastSignature = null
                HvacLog.w("cluster.mcprobe: oneosapi disconnected")
            }
        }
    }

    /** Idempotent arm; the caller checked the settings switch. Safe from any thread. */
    @Synchronized
    fun ensureStarted(context: Context) {
        if (running) return
        val app = context.applicationContext
        running = true
        appContext = app
        lastSignature = null
        pollCount = 0
        thread = HandlerThread("cluster-mcprobe").apply { start() }
        handler = Handler(thread!!.looper)
        bound = false
        runCatching {
            bound = app.bindService(
                Intent().setComponent(ComponentName(SVC_PACKAGE, SVC_CLASS)),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.onFailure { HvacLog.e("cluster.mcprobe: bind threw", it) }
        if (!bound) HvacLog.w("cluster.mcprobe: bindService returned false (service missing on this unit?)")
        HvacLog.i("cluster.mcprobe: armed (poll every ${POLL_MS / 1000} s, source=BT)")
        handler!!.postDelayed({ pollLoop() }, POLL_MS)
    }

    @Volatile private var bound = false

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        val app = appContext
        if (bound && app != null) runCatching { app.unbindService(connection) }
        bound = false
        serviceManager = null
        musicManager = null
        appContext = null
        HvacLog.i("cluster.mcprobe: disarmed")
    }

    private fun pollLoop() {
        if (!running) return
        pollCount++
        runCatching { pollOnce() }.onFailure { logFailure("poll threw", it) }
        handler?.postDelayed({ pollLoop() }, POLL_MS)
    }

    private fun pollOnce() {
        val manager = resolveMusicManager() ?: return
        val playState = intCall(manager, TOKEN_MUSIC_MANAGER, TX_GET_CURRENT_PLAY_STATE) {
            it.writeInt(BT_SOURCE)
        }
        val data = mediaDataCall(manager, TX_GET_CURRENT_MEDIA_DATA)
        if (playState == null && data == null) return
        val cover = data?.cover
        val signature = "${playState ?: -9}|${data?.id}|${data?.name}|${data?.artist}|" +
            "${data?.albumName}|${cover != null}|${data?.albumCoverUri}|${data?.duration}"
        val heartbeat = pollCount % HEARTBEAT_POLLS == 0
        if (signature != lastSignature || heartbeat) {
            lastSignature = signature
            val coverNote = when {
                cover != null -> "bitmap ${cover.width}x${cover.height}"
                data?.albumCoverUri != null -> "uri ${data.albumCoverUri}"
                else -> "none"
            }
            HvacLog.i(
                "cluster.mcprobe: BT feed play=$playState title=${data?.name ?: "-"} " +
                    "artist=${data?.artist ?: "-"} album=${data?.albumName ?: "-"} " +
                    "dur=${data?.duration ?: "-"} cover=$coverNote" +
                    (if (heartbeat) " (heartbeat)" else "")
            )
            cover?.let { saveCover(it) }
        }
    }

    /** The chain IServiceManager.getService(3) → IMediaCenter.getMusicManager(), memoized. */
    private fun resolveMusicManager(): IBinder? {
        musicManager?.let { return it }
        val manager = serviceManager ?: return null
        val descriptor = runCatching { manager.interfaceDescriptor }.getOrNull()
        if (descriptor != TOKEN_SERVICE_MANAGER) {
            logFailure("descriptor mismatch: $descriptor (expected $TOKEN_SERVICE_MANAGER)")
            return null
        }
        val center = binderCall(manager, TOKEN_SERVICE_MANAGER, TX_GET_SERVICE) {
            it.writeInt(MEDIA_CENTER_SERVICE_ID)
        }?.readStrongBinder() ?: return null
        if (runCatching { center.interfaceDescriptor }.getOrNull() != TOKEN_MEDIA_CENTER) {
            logFailure("media center descriptor mismatch")
            return null
        }
        val music = binderCall(center, TOKEN_MEDIA_CENTER, TX_GET_MUSIC_MANAGER) {}?.readStrongBinder()
        musicManager = music
        if (music != null) HvacLog.i("cluster.mcprobe: music manager resolved")
        return music
    }

    /** Transact, read the status envelope, return the reply (caller recycles it). */
    private fun binderCall(
        target: IBinder,
        token: String,
        code: Int,
        write: (Parcel) -> Unit,
    ): Parcel? {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(token)
            write(request)
            target.transact(code, request, reply, 0)
            reply.readException()
            reply
        } catch (e: RemoteException) {
            logFailure("transact $code failed", e)
            reply.recycle()
            null
        } finally {
            request.recycle()
        }
    }

    private fun intCall(target: IBinder, token: String, code: Int, write: (Parcel) -> Unit): Int? =
        binderCall(target, token, code, write)?.let { reply ->
            try {
                reply.readInt()
            } finally {
                reply.recycle()
            }
        }

    /** MediaData fields in readFromParcel order (reference stub, exact sequence). */
    private fun mediaDataCall(target: IBinder, code: Int): MediaDataView? {
        val reply = binderCall(target, TOKEN_MUSIC_MANAGER, code) { it.writeInt(BT_SOURCE) } ?: return null
        return try {
            if (reply.readInt() == 0) null
            else MediaDataView(
                id = reply.readString(),
                artist = reply.readString(),
                name = reply.readString(),
                duration = reply.readLong(),
                uri = reply.readString(),
                cover = reply.readParcelable<Bitmap>(Bitmap::class.java.classLoader),
                albumCoverUri = reply.readString(),
                albumName = reply.readString(),
            )
        } finally {
            reply.recycle()
        }
    }

    private data class MediaDataView(
        val id: String?,
        val artist: String?,
        val name: String?,
        val duration: Long,
        val uri: String?,
        val cover: Bitmap?,
        val albumCoverUri: String?,
        val albumName: String?,
    )

    /** One overwritten file — the planner can open what the stock feed sees, no churn. */
    private fun saveCover(cover: Bitmap) {
        val context = appContext ?: return
        runCatching {
            val dir = context.getExternalFilesDir("probe") ?: return
            val file = File(dir, "bt_cover.jpg")
            FileOutputStream(file).use { out ->
                cover.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            HvacLog.i("cluster.mcprobe: stock cover saved → ${file.absolutePath} (${cover.width}x${cover.height})")
        }.onFailure { logFailure("cover save failed", it) }
    }

    private fun logFailure(message: String, tr: Throwable? = null) {
        if (failureLogged) return
        failureLogged = true
        HvacLog.e("cluster.mcprobe: $message (once, retried silently)", tr)
    }
}
