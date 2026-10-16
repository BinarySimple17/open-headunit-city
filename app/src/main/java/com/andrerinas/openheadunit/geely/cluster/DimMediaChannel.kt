package com.andrerinas.openheadunit.geely.cluster

import android.content.Context
import com.andrerinas.openheadunit.geely.hvac.HvacLog
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
 */
object DimMediaChannel {

    private const val DIM_CLASS = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction"
    private const val PLAYBACK_INFO_CLASS =
        "com.ecarx.xui.adaptapi.diminteraction.IMediaInteraction\$IPlaybackInfo"

    @Volatile private var mediaInteraction: Any? = null
    @Volatile private var updatePlaybackInfo: Method? = null
    @Volatile private var updateCurrentProgress: Method? = null
    @Volatile private var playbackInfoInterface: Class<*>? = null

    val available: Boolean get() = mediaInteraction != null

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
            HvacLog.i("cluster.dim: media interaction ready (publish=${publish.parameterTypes[0].name})")
            true
        } catch (_: ClassNotFoundException) {
            fail("$DIM_CLASS not on the classpath — not a Geely/ECARX build")
        } catch (e: InvocationTargetException) {
            fail("cluster.dim: init threw", e.cause ?: e)
        } catch (e: Throwable) {
            fail("cluster.dim: init failed", e)
        }
    }

    /** The interface the card proxy must be built over; null until [connect] succeeded. */
    fun playbackInfoInterface(): Class<*>? = playbackInfoInterface

    /** Pushes a whole card ([PlaybackInfoProxy] over [playbackInfoInterface]); true when accepted. */
    fun publish(info: Any): Boolean {
        val method = updatePlaybackInfo ?: return fail("publish before connect")
        return try {
            method.invoke(mediaInteraction, info)
            true
        } catch (e: Throwable) {
            fail("updatePlaybackInfo threw", e.cause ?: e)
        }
    }

    /** Position bar only — same-track ticks use this, never a full re-publish (reference dedup). */
    fun publishProgress(positionMs: Long): Boolean {
        val method = updateCurrentProgress ?: return fail("progress before connect")
        return try {
            method.invoke(mediaInteraction, positionMs)
            true
        } catch (e: Throwable) {
            fail("updateCurrentProgress threw", e.cause ?: e)
        }
    }

    private fun fail(message: String, tr: Throwable? = null): Boolean {
        HvacLog.e("cluster.dim: $message", tr)
        return false
    }
}
