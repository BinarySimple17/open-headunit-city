package com.andrerinas.openheadunit.geely.cluster

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.geely.hvac.HvacLog
import com.geely.mockcar.IDimMediaMock
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * The instrument-cluster media card channel (F013), reached reflectively: DimInteraction and
 * friends ship with the firmware (boot classpath), no APK carries them (конспект §2c — the
 * reference app implements none of the adaptapi classes). The chain is two lookups:
 * `DimInteraction.create(context)` → `getMediaInteraction()`, then the cluster is driven by
 * `updatePlaybackInfo(IPlaybackInfo)` — a full card or an empty one to clear — and
 * `updateCurrentProgress(ms)` for the position bar. No permissions are required (reference
 * fact: its manifest carries none of the car ones).
 *
 * connect() is blocking (binds the cluster's service) and must run off the main thread;
 * publish calls are safe from any thread once connected. Every failure is graceful — the
 * card silently never appears and the reason lands in HVAC-лог.
 *
 * Mock channel (F014): on a non-Geely build (firmware classes absent) a DEBUG build binds
 * the mock-car app ([IDimMediaMock]) instead and feeds it the very same proxy — built over
 * [MockPlaybackInfo], extracted getter-by-getter — so gating, arbitration, dedup and the
 * artwork pipeline are rehearsed on an emulator with zero bridge changes. Release builds
 * never bind the mock; a real Geely device never reaches the fallback (firmware wins first).
 */
object DimMediaChannel {

    private const val DIM_CLASS = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction"
    private const val PLAYBACK_INFO_CLASS =
        "com.ecarx.xui.adaptapi.diminteraction.IMediaInteraction\$IPlaybackInfo"
    private const val MOCK_PACKAGE = "com.geely.mockcar"
    private const val MOCK_SERVICE = "com.geely.mockcar.MockCarService"
    private const val MOCK_ACTION = "com.geely.mockcar.DIM_BIND"

    private enum class Mode { NONE, FIRMWARE, MOCK }

    @Volatile private var mode: Mode = Mode.NONE
    @Volatile private var mediaInteraction: Any? = null
    @Volatile private var updatePlaybackInfo: Method? = null
    @Volatile private var updateCurrentProgress: Method? = null
    @Volatile private var playbackInfoInterface: Class<*>? = null
    @Volatile private var mock: IDimMediaMock? = null

    private var mockConnection: ServiceConnection? = null

    val available: Boolean get() = mode != Mode.NONE

    /** Blocking connect; returns false — never throws — with the reason in [HvacLog]. */
    fun connect(context: Context): Boolean {
        if (available) return true
        return try {
            val dimClass = Class.forName(DIM_CLASS)
            val create = dimClass.methods.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.name == "create" &&
                    it.parameterTypes.size == 1 && it.parameterTypes[0] == Context::class.java
            } ?: return fail("$DIM_CLASS.create(Context) not found")
            val dim = create.invoke(null, context.applicationContext)
                ?: return fail("DimInteraction.create(context) returned null")

            val media = dim.javaClass.methods
                .firstOrNull { it.name == "getMediaInteraction" && it.parameterTypes.isEmpty() }
                ?.invoke(dim)
                ?: return fail("DimInteraction.getMediaInteraction() missing or returned null")

            val info = Class.forName(PLAYBACK_INFO_CLASS)
            val publish = media.javaClass.methods.firstOrNull {
                it.name == "updatePlaybackInfo" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isInterface
            } ?: return fail("updatePlaybackInfo(IPlaybackInfo) not found")
            val progress = media.javaClass.methods.firstOrNull {
                it.name == "updateCurrentProgress" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Long::class.javaPrimitiveType
            } ?: return fail("updateCurrentProgress(J) not found")

            updatePlaybackInfo = publish
            updateCurrentProgress = progress
            playbackInfoInterface = info
            mediaInteraction = media
            mode = Mode.FIRMWARE
            HvacLog.i("cluster.dim: media interaction ready (publish=${publish.parameterTypes[0].name})")
            true
        } catch (_: ClassNotFoundException) {
            if (BuildConfig.DEBUG) connectMock(context)
            else fail("$DIM_CLASS not on the classpath — not a Geely/ECARX build")
        } catch (e: InvocationTargetException) {
            fail("cluster.dim: init threw", e.cause ?: e)
        } catch (e: Throwable) {
            fail("cluster.dim: init failed", e)
        }
    }

    /** The interface the card proxy must be built over; null until [connect] succeeded. */
    fun playbackInfoInterface(): Class<*>? = playbackInfoInterface

    /** Pushes a whole card ([PlaybackInfoProxy] over [playbackInfoInterface]); true when accepted. */
    fun publish(info: Any): Boolean = when (mode) {
        Mode.FIRMWARE -> {
            val method = updatePlaybackInfo ?: return fail("publish before connect")
            try {
                method.invoke(mediaInteraction, info)
                true
            } catch (e: Throwable) {
                fail("updatePlaybackInfo threw", e.cause ?: e)
            }
        }
        Mode.MOCK -> {
            val stub = mock ?: return mockLost("publish")
            try {
                stub.updatePlaybackInfo(cardBundle(info))
            } catch (e: Throwable) {
                fail("mock updatePlaybackInfo threw", e)
                mockLost("publish")
            }
        }
        Mode.NONE -> {
            // A caller without a connected channel: the AA stop path ticks progress even when
            // nothing was ever published — routine on non-Geely builds, so debug-level only.
            HvacLog.d("cluster.dim: publish/progress skipped — channel not connected")
            false
        }
    }

    /** Position bar only — same-track ticks use this, never a full re-publish (reference dedup). */
    fun publishProgress(positionMs: Long): Boolean = when (mode) {
        Mode.FIRMWARE -> {
            val method = updateCurrentProgress ?: return fail("progress before connect")
            try {
                method.invoke(mediaInteraction, positionMs)
                true
            } catch (e: Throwable) {
                fail("updateCurrentProgress threw", e.cause ?: e)
            }
        }
        Mode.MOCK -> {
            val stub = mock ?: return mockLost("progress")
            try {
                stub.updateCurrentProgress(positionMs)
                true
            } catch (e: Throwable) {
                fail("mock updateCurrentProgress threw", e)
                mockLost("progress")
            }
        }
        Mode.NONE -> {
            HvacLog.d("cluster.dim: progress skipped — channel not connected")
            false
        }
    }

    /** The stand-in went away (update or force-stop drops the binder): reset the mode so the
     *  next caller re-runs [connect] and rebinds — the same self-heal AidlMockCarPort needs. */
    private fun mockLost(what: String): Boolean {
        HvacLog.w("cluster.dim: mock channel lost ($what) — will rebind on the next publish")
        synchronized(this) {
            if (mode == Mode.MOCK) mode = Mode.NONE
            mock = null
        }
        return false
    }

    /** Blocking bind of the mock-car service, timed retries like AidlMockCarPort. */
    private fun connectMock(context: Context): Boolean {
        HvacLog.i("cluster.dim: firmware classes absent — mock channel (debug)")
        val app = context.applicationContext
        val latch = java.util.concurrent.CountDownLatch(1)
        var stub: IDimMediaMock? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val asInterface = binder?.let { IDimMediaMock.Stub.asInterface(it) }
                synchronized(this@DimMediaChannel) {
                    stub = if (asInterface?.asBinder()?.isBinderAlive == true) asInterface else null
                }
                HvacLog.i("cluster.dim: mock channel connected (alive=${stub != null})")
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                synchronized(this@DimMediaChannel) { mock = null }
                HvacLog.w("cluster.dim: mock channel disconnected")
            }
        }
        val intent = Intent(MOCK_ACTION).setClassName(MOCK_PACKAGE, MOCK_SERVICE)
        for (attempt in 1..MOCK_ATTEMPTS) {
            try {
                app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: Throwable) {
                HvacLog.e("cluster.dim: mock bind attempt $attempt threw", e)
            }
            if (latch.await(MOCK_RETRY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) break
            HvacLog.w("cluster.dim: mock channel not up after attempt $attempt/$MOCK_ATTEMPTS")
        }
        if (stub == null) {
            runCatching { app.unbindService(connection) }
            return fail("mock channel unreachable — install com.geely.mockcar or ship a real build")
        }
        mock = stub
        mockConnection = connection
        playbackInfoInterface = MockPlaybackInfo::class.java
        mode = Mode.MOCK
        HvacLog.i("cluster.dim: mock channel ready (publish=${MockPlaybackInfo::class.java.name})")
        return true
    }

    /** One Bundle per getter name — the stand-in reads the same names the real cluster pulls. */
    private fun cardBundle(info: Any): Bundle {
        val bundle = Bundle()
        MockPlaybackInfo.extract(info).forEach { (getter, value) ->
            when (value) {
                null -> {}
                is Int -> bundle.putInt(getter, value)
                is Long -> bundle.putLong(getter, value)
                is String -> bundle.putString(getter, value)
            }
        }
        return bundle
    }

    private fun fail(message: String, tr: Throwable? = null): Boolean {
        HvacLog.e("cluster.dim: $message", tr)
        return false
    }

    private const val MOCK_ATTEMPTS = 3
    private const val MOCK_RETRY_MS = 1_500L
}
