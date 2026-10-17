package com.andrerinas.openheadunit.geely.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pull-side contract of the cluster card (F013): the proxy answers the firmware's
 * getters by name. The name set here mirrors the reference implementation's exact list
 * (GMediaHud d4/I, конспект §2c) — a getter the firmware asks for that is not in the table
 * must answer its return type's default, never crash (session trap #1).
 */
class PlaybackInfoProxyTest {

    /** Stringly stand-in for the firmware interface: same getter names, JVM-safe types. */
    private interface TestPlaybackInfo {
        fun getTitle(): String?
        fun getArtist(): String?
        fun getAlbum(): String?
        fun getArtwork(): String?
        fun getNextArtwork(): String?
        fun getDuration(): Long
        fun getPlaybackStatus(): Int
        fun getSourceType(): Int
        fun getLoopMode(): Int
        fun getFavoriteState(): Int
        fun getUUID(): String?
        fun getLyricContent(): String?
        fun getRadioFrequency(): String?
        fun getSomethingTheFirmwareAddedLater(): Int
        fun getUnknownObject(): String?
    }

    private val snapshot = TrackSnapshot(
        title = "Song",
        artist = "Artist",
        album = "Album",
        artworkUri = "file:///pictures/ohu-media/x.jpg",
        durationMs = 200_000L,
        isPlaying = true,
        trackUuid = "abc123",
    )

    private fun proxy(s: TrackSnapshot = snapshot): TestPlaybackInfo =
        PlaybackInfoProxy.create(TestPlaybackInfo::class.java, s) as TestPlaybackInfo

    @Test
    fun `the card getters answer from the snapshot`() {
        val info = proxy()
        assertEquals("Song", info.getTitle())
        assertEquals("Artist", info.getArtist())
        assertEquals("Album", info.getAlbum())
        assertEquals("file:///pictures/ohu-media/x.jpg", info.getArtwork())
        assertEquals(200_000L, info.getDuration())
        assertEquals("abc123", info.getUUID())
    }

    @Test
    fun `playback status is the reference's boolean flag`() {
        assertEquals(1, proxy().getPlaybackStatus())
        assertEquals(0, proxy(snapshot.copy(isPlaying = false)).getPlaybackStatus())
    }

    @Test
    fun `unsupplied getters answer the reference's literal values`() {
        val info = proxy()
        // d4/I: the next cover answers the same uri as the current one; previous stays null.
        assertEquals("file:///pictures/ohu-media/x.jpg", info.getNextArtwork())
        assertEquals("", info.getLyricContent())
        // Round 6 (2026-10-17): sourceType 0 left the card without artwork; the reference
        // ships constant 6 (per-track -1 → user setting → 6). favorite is 1, not 0.
        assertEquals(6, info.getSourceType())
        assertEquals(1, info.getFavoriteState())
        assertEquals(0, info.getLoopMode())
    }

    @Test
    fun `radio getters answer an empty String, not a number - the firmware casts them during the push`() {
        // Round 5 (2026-10-17 08:20): getRadioFrequency returned Integer 0 and the firmware's
        // MediaInteraction.updatePlaybackInfo:93 threw ClassCastException — one wrong-typed
        // getter killed the whole card push, twice in a row. The reference answers "".
        assertEquals("", proxy().getRadioFrequency())
    }

    @Test
    fun `a getter we do not know answers its type's default, not a crash`() {
        assertEquals(0, proxy().getSomethingTheFirmwareAddedLater())
        assertNull(proxy().getUnknownObject())
    }

    @Test
    fun `the empty card answers all neutral`() {
        val info = proxy(TrackSnapshot.EMPTY)
        assertNull(info.getTitle())
        assertNull(info.getArtist())
        assertNull(info.getArtwork())
        assertNull(info.getUUID())
        assertEquals(0L, info.getDuration())
        assertEquals(0, info.getPlaybackStatus())
    }
}
