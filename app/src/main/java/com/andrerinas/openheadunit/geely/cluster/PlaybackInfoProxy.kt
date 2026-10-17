package com.andrerinas.openheadunit.geely.cluster

import android.net.Uri
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * The per-track card the cluster pulls through the IPlaybackInfo proxy. The interface is
 * pull-style: after we push a card with updatePlaybackInfo, the cluster reads the getters
 * at its own pace, so the snapshot must stay consistent on its own.
 */
data class TrackSnapshot(
    val title: String?,
    val artist: String?,
    val album: String?,
    /** file:// uri of the cached cover, or null. */
    val artworkUri: String?,
    /** Track length in ms; -1 = unknown — the reference's own "no duration" marker. */
    val durationMs: Long,
    val isPlaying: Boolean,
    /** Stable per-track id so a re-publish of the same track reads as the same card. */
    val trackUuid: String?,
) {
    companion object {
        /** The reference's empty implementation: every getter answers its neutral default. */
        val EMPTY = TrackSnapshot(null, null, null, null, 0L, false, null)
    }
}

/**
 * The dynamic IPlaybackInfo implementation (F013). The interface lives in the firmware — no
 * APK carries the adaptapi classes — so the proxy is built against the loaded Class and
 * answers getters BY NAME. The name set below is the reference implementation's exact list
 * (GMediaHud d4/I, конспект §2c); a getter the running firmware asks for that is not in the
 * table gets its return type's default, never a crash (session trap #1).
 */
object PlaybackInfoProxy {

    fun create(infoInterface: Class<*>, snapshot: TrackSnapshot): Any = Proxy.newProxyInstance(
        infoInterface.classLoader,
        arrayOf(infoInterface),
        Handler(snapshot),
    )

    private class Handler(private val snapshot: TrackSnapshot) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) return anyMethod(proxy, method, args)
            return adapt(valueFor(snapshot, method.name), method.returnType)
        }

        private fun anyMethod(proxy: Any, method: Method, args: Array<out Any?>?): Any = when (method.name) {
            "toString" -> "TrackSnapshot($snapshot)"
            "hashCode" -> snapshot.hashCode()
            "equals" -> proxy === args?.get(0)
            else -> throw IllegalStateException("unexpected Object method: ${method.name}")
        }
    }

    /** Which snapshot field answers a getter name. Names are pinned by PlaybackInfoProxyTest. */
    internal fun valueFor(snapshot: TrackSnapshot, getter: String): Any? = when (getter) {
        "getTitle" -> snapshot.title
        "getArtist" -> snapshot.artist
        "getAlbum" -> snapshot.album
        "getArtwork" -> snapshot.artworkUri
        // d4/I literally: the next cover answers the SAME uri as the current one; previous,
        // lyrics file and media path are null.
        "getNextArtwork" -> snapshot.artworkUri
        "getPreviousArtwork", "getLyric", "getMediaPath" -> null
        // Radio answers are Strings and the firmware casts them during the push — round 5: an
        // Integer here aborted the whole card with ClassCastException at
        // MediaInteraction.updatePlaybackInfo:93. The reference answers "" (never null) here
        // and for the lyric texts — d4/I const-string "".
        "getRadioFrequency", "getRadioStationName", "getLyricContent", "getCurrentLyricSentence" -> ""
        "getDuration" -> snapshot.durationMs
        // The reference models playback status as a plain boolean flag (d4/I reads a Z field).
        "getPlaybackStatus" -> if (snapshot.isPlaying) 1 else 0
        // d4/I literally: loop 0, favorite 1, radioMode 0, queuePosition 0. sourceType falls
        // through the reference's per-track value and user setting to constant 6 — round 6
        // (2026-10-17): our 0 reached the firmware (sources =:0 in the car log) and the card
        // came up without artwork; 6 is the value the working reference ships by default.
        "getLoopMode", "getRadioMode", "getPlayingItemPositionInQueue" -> 0
        "getFavoriteState" -> 1
        "getSourceType" -> 6
        "getUUID" -> snapshot.trackUuid
        else -> null
    }

    /**
     * Boxes [value] into whatever type this firmware's getter declares. Type-strict: the
     * firmware casts the proxy's results itself (round 5 proved a wrong type aborts the
     * entire push), so a mismatch degrades to the type's neutral instead of leaking through.
     */
    private fun adapt(value: Any?, type: Class<*>): Any? = when (type) {
        Uri::class.java -> (value as? String)?.let { Uri.parse(it) }
        java.lang.Integer.TYPE, java.lang.Integer::class.java -> value as? Int ?: 0
        java.lang.Long.TYPE, java.lang.Long::class.java -> value as? Long ?: 0L
        java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> value as? Boolean ?: false
        else -> value as? String
    }
}
