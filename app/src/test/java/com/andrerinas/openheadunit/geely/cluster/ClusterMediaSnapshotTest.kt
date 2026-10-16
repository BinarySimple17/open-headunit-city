package com.andrerinas.openheadunit.geely.cluster

import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The AA-metadata → cluster-card mapping (F013): the same MediaMetaData the native media
 * session consumes becomes a [TrackSnapshot]. AA carries duration in whole seconds; missing
 * fields degrade to the cluster's neutral values, not empty-string noise.
 */
class ClusterMediaSnapshotTest {

    private fun meta(
        song: String = "",
        artist: String = "",
        album: String = "",
        durationSeconds: Int? = null,
    ): MediaPlayback.MediaMetaData = MediaPlayback.MediaMetaData.newBuilder().apply {
        this.song = song
        this.artist = artist
        this.album = album
        durationSeconds?.let { this.durationSeconds = it }
    }.build()

    @Test
    fun `track fields map one to one`() {
        val snapshot = ClusterMediaBridge.snapshotFrom(
            meta(song = "Song", artist = "Artist", album = "Album", durationSeconds = 200),
            isPlaying = true,
            artworkUri = "file:///tmp/x.jpg",
        )
        assertEquals("Song", snapshot.title)
        assertEquals("Artist", snapshot.artist)
        assertEquals("Album", snapshot.album)
        assertEquals("file:///tmp/x.jpg", snapshot.artworkUri)
        assertEquals(200_000L, snapshot.durationMs)
        assertEquals(true, snapshot.isPlaying)
    }

    @Test
    fun `missing duration is unknown, not zero`() {
        // The reference uses -1 as its "no duration" marker and falls back on it.
        assertEquals(-1L, ClusterMediaBridge.durationOf(meta(song = "s")))
        assertEquals(0L, ClusterMediaBridge.durationOf(
            meta(song = "s").toBuilder().setDurationSeconds(0).build()
        ))
    }

    @Test
    fun `empty strings become nulls`() {
        val snapshot = ClusterMediaBridge.snapshotFrom(meta(), isPlaying = false, artworkUri = null)
        assertNull(snapshot.title)
        assertNull(snapshot.artist)
        assertNull(snapshot.album)
        assertNull(snapshot.artworkUri)
        assertNull(snapshot.trackUuid)
        assertEquals(false, snapshot.isPlaying)
    }

    @Test
    fun `the track uuid is stable per track and differs across tracks`() {
        val first = ClusterMediaBridge.snapshotFrom(
            meta(song = "Song", artist = "Artist"), isPlaying = false, artworkUri = null)
        val replay = ClusterMediaBridge.snapshotFrom(
            meta(song = "Song", artist = "Artist"), isPlaying = true, artworkUri = "file:///a.png")
        val other = ClusterMediaBridge.snapshotFrom(
            meta(song = "Song", artist = "Someone else"), isPlaying = false, artworkUri = null)
        assertEquals(first.trackUuid, replay.trackUuid)
        assertNotEquals(first.trackUuid, other.trackUuid)
    }

    @Test
    fun `the empty card is the neutral snapshot`() {
        val empty = TrackSnapshot.EMPTY
        assertNull(empty.title)
        assertNull(empty.artworkUri)
        assertEquals(0L, empty.durationMs)
        assertEquals(false, empty.isPlaying)
    }
}
