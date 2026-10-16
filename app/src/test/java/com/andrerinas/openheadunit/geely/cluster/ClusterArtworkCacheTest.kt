package com.andrerinas.openheadunit.geely.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cover cache rules (F013): files are named by the track's hash (a repeated track keeps
 * one file), the picture type follows the magic bytes, and the LRU rotation drops beyond the
 * limit keeping the newest.
 */
class ClusterArtworkCacheTest {

    @Test
    fun `the name is a stable hash of the track key plus the sniffed type`() {
        val first = ClusterArtworkCache.fileNameFor("track-1", "jpg")
        val again = ClusterArtworkCache.fileNameFor("track-1", "jpg")
        val other = ClusterArtworkCache.fileNameFor("track-2", "jpg")
        assertEquals(first, again)
        assertNotEquals(first, other)
        assertTrue("prefix", first.startsWith("ohu_media_"))
        assertTrue("16 hex chars + .jpg", Regex("^ohu_media_[0-9a-f]{16}\\.jpg$").matches(first))
    }

    @Test
    fun `picture type follows the magic bytes`() {
        assertEquals("jpg", sniffImageType(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertEquals("png", sniffImageType(byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())))
        assertEquals("img", sniffImageType(byteArrayOf(1, 2, 3)))
        assertEquals("img", sniffImageType(byteArrayOf()))
    }

    @Test
    fun `mime follows the extension - unknown bytes ride as jpeg`() {
        assertEquals("image/jpeg", mimeOf("ohu_media_x.jpg"))
        assertEquals("image/png", mimeOf("ohu_media_x.png"))
        assertEquals("image/jpeg", mimeOf("ohu_media_x.img"))
    }

    @Test
    fun `rotation drops beyond the limit keeping the newest`() {
        val entries = (1..30).map { ClusterArtworkCache.FileEntry(key = "file-$it", stamp = it.toLong()) }
        val drop = ClusterArtworkCache.dropBeyondLimit(entries, limit = 24)
        assertEquals(6, drop.size)
        // The six oldest (lowest stamps) go; the newest 24 stay.
        assertEquals((1..6).map { "file-$it" }.toSet(), drop.toSet())
    }

    @Test
    fun `rotation at or under the limit drops nothing`() {
        val entries = (1..24).map { ClusterArtworkCache.FileEntry(key = "file-$it", stamp = it.toLong()) }
        assertTrue(ClusterArtworkCache.dropBeyondLimit(entries, limit = 24).isEmpty())
        assertTrue(ClusterArtworkCache.dropBeyondLimit(emptyList(), limit = 24).isEmpty())
    }
}
