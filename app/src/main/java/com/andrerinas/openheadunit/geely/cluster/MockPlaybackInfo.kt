package com.andrerinas.openheadunit.geely.cluster

/**
 * The local stand-in for the firmware's IPlaybackInfo (F014): the same getter NAMES the d4/I
 * reference implementation answers (конспект §2c), so the production path —
 * `PlaybackInfoProxy.create(interfaceClass, snapshot)` — can be built over this interface
 * verbatim when the firmware classes are absent (debug mock channel). The mock channel
 * extracts a card by invoking these getters on the proxy BY NAME; the name set is pinned by
 * MockPlaybackInfoTest.
 *
 * Uri-typed getters of the real interface are String here on purpose: the transport carries
 * uris as strings anyway, and android.net.Uri is an untestable stub in JVM unit tests.
 */
interface MockPlaybackInfo {
    fun getTitle(): String?
    fun getArtist(): String?
    fun getAlbum(): String?
    fun getArtwork(): String?
    fun getNextArtwork(): String?
    fun getPreviousArtwork(): String?
    fun getLyric(): String?
    fun getMediaPath(): String?
    fun getDuration(): Long?
    fun getPlaybackStatus(): Int?
    fun getSourceType(): Int?
    fun getFavoriteState(): Int?
    fun getLoopMode(): Int?
    fun getRadioMode(): Int?
    fun getPlayingItemPositionInQueue(): Int?
    fun getRadioFrequency(): String?
    fun getRadioStationName(): String?
    fun getLyricContent(): String?
    fun getCurrentLyricSentence(): String?
    fun getUUID(): String?

    companion object {

        /** Getter names in d4/I order — extraction and its test are pinned to this list. */
        internal val GETTERS: List<String> = listOf(
            "getTitle", "getArtist", "getAlbum", "getArtwork", "getNextArtwork",
            "getPreviousArtwork", "getLyric", "getMediaPath", "getDuration",
            "getPlaybackStatus", "getSourceType", "getFavoriteState", "getLoopMode",
            "getRadioMode", "getPlayingItemPositionInQueue", "getRadioFrequency",
            "getRadioStationName", "getLyricContent", "getCurrentLyricSentence", "getUUID",
        )

        /**
         * Pulls a card out of a proxy: every getter invoked by name, a failing getter degrades
         * to null rather than killing the card.
         */
        internal fun extract(info: Any): Map<String, Any?> {
            val methods = MockPlaybackInfo::class.java.methods.associateBy { it.name }
            return GETTERS.associateWith { getter ->
                val method = methods[getter]
                method?.let { runCatching { it.invoke(info) }.getOrNull() }
            }
        }
    }
}
