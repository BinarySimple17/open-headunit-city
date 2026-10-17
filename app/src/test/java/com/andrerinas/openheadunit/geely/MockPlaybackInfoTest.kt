package com.andrerinas.openheadunit.geely

import com.andrerinas.openheadunit.geely.cluster.MockPlaybackInfo
import com.andrerinas.openheadunit.geely.cluster.PlaybackInfoProxy
import com.andrerinas.openheadunit.geely.cluster.TrackSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mock channel's contract (F014): the production proxy is built over the local
 * [MockPlaybackInfo] interface and extracted getter-by-getter into the transport map. The
 * name set and the values must match what the real firmware would pull — anything else and
 * the stand-in would show a card the car would never see.
 */
class MockPlaybackInfoTest {

    private fun extractOf(snapshot: TrackSnapshot): Map<String, Any?> =
        MockPlaybackInfo.extract(PlaybackInfoProxy.create(MockPlaybackInfo::class.java, snapshot))

    @Test
    fun `full card carries every getter the cluster would pull`() {
        val card = extractOf(
            TrackSnapshot(
                title = "Song", artist = "Artist", album = "Album",
                artworkUri = "file:///storage/emulated/0/Pictures/ohu-media/ohu_media_ab.jpg",
                durationMs = 200_000L, isPlaying = true, trackUuid = "uuid-1",
            )
        )
        // Every documented getter present — the stand-in's bundle is complete.
        assertEquals(MockPlaybackInfo.GETTERS, card.keys.toList())
        assertEquals("Song", card["getTitle"])
        assertEquals("Artist", card["getArtist"])
        assertEquals("Album", card["getAlbum"])
        // Uri getters flatten to strings; nextArtwork duplicates artwork (d4/I literally).
        assertEquals("file:///storage/emulated/0/Pictures/ohu-media/ohu_media_ab.jpg", card["getArtwork"])
        assertEquals(card["getArtwork"], card["getNextArtwork"])
        assertEquals(null, card["getPreviousArtwork"])
        assertEquals("uuid-1", card["getUUID"])
        assertEquals(200_000L, card["getDuration"])
        assertEquals(1, card["getPlaybackStatus"])
        // The reference's exact defaults (d4/I): favorite=1, loop/radio/queue=0, sourceType=6.
        assertEquals(1, card["getFavoriteState"])
        assertEquals(0, card["getLoopMode"])
        assertEquals(0, card["getRadioMode"])
        assertEquals(0, card["getPlayingItemPositionInQueue"])
        assertEquals(6, card["getSourceType"])
        // Radio and lyric texts answer "" never null — the firmware casts them to String.
        assertEquals("", card["getRadioFrequency"])
        assertEquals("", card["getRadioStationName"])
        assertEquals("", card["getLyricContent"])
        assertEquals("", card["getCurrentLyricSentence"])
    }

    @Test
    fun `empty card is the clear - neutral defaults for every getter`() {
        val card = extractOf(TrackSnapshot.EMPTY)
        assertEquals(null, card["getTitle"])
        assertEquals(null, card["getArtist"])
        assertEquals(null, card["getAlbum"])
        assertEquals(null, card["getArtwork"])
        assertEquals(null, card["getUUID"])
        assertEquals(0L, card["getDuration"])
        assertEquals(0, card["getPlaybackStatus"])
        // The pinned constants survive the empty card exactly as the firmware would read them.
        assertEquals(6, card["getSourceType"])
        assertEquals(1, card["getFavoriteState"])
        assertEquals("", card["getRadioFrequency"])
    }

    @Test
    fun `paused track reports status 0`() {
        val card = extractOf(TrackSnapshot.EMPTY.copy(title = "T", isPlaying = false))
        assertEquals(0, card["getPlaybackStatus"])
    }

    @Test
    fun `extraction survives a getter throwing - value degrades to null`() {
        val hostile = object : MockPlaybackInfo {
            override fun getTitle(): String? = throw IllegalStateException("boom")
            override fun getArtist(): String? = "A"
            override fun getAlbum(): String? = null
            override fun getArtwork(): String? = null
            override fun getNextArtwork(): String? = null
            override fun getPreviousArtwork(): String? = null
            override fun getLyric(): String? = null
            override fun getMediaPath(): String? = null
            override fun getDuration(): Long? = 5L
            override fun getPlaybackStatus(): Int? = 1
            override fun getSourceType(): Int? = 6
            override fun getFavoriteState(): Int? = 1
            override fun getLoopMode(): Int? = 0
            override fun getRadioMode(): Int? = 0
            override fun getPlayingItemPositionInQueue(): Int? = 0
            override fun getRadioFrequency(): String? = ""
            override fun getRadioStationName(): String? = ""
            override fun getLyricContent(): String? = ""
            override fun getCurrentLyricSentence(): String? = ""
            override fun getUUID(): String? = "u"
        }
        val card = MockPlaybackInfo.extract(hostile)
        assertTrue(card.containsKey("getTitle"))
        assertEquals(null, card["getTitle"])
        assertEquals("A", card["getArtist"])
    }
}
