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
        fun getUUID(): String?
        fun getLyricContent(): String?
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
    fun `unsupplied getters answer neutral`() {
        val info = proxy()
        assertNull(info.getNextArtwork())
        assertNull(info.getLyricContent())
        assertEquals(0, info.getSourceType())
        assertEquals(0, info.getLoopMode())
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
