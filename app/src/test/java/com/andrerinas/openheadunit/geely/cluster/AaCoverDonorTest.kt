package com.andrerinas.openheadunit.geely.cluster

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The AA cover donor (S014, model A): art bytes keyed by normalized title|artist. A
 * mismatch must answer "no cover", never a foreign cover — the card's fail-safe.
 */
class AaCoverDonorTest {

    @Test
    fun `same track matches across spelling differences`() {
        val donor = AaCoverDonor()
        donor.offer("  Making   Evenings ", "Lake of TOARS".replace("TOARS", "Tears"), byteArrayOf(1))
        assertArrayEquals(byteArrayOf(1), donor.coverFor("making evenings", "lake of tears"))
        // The Bluetooth card's exact spelling resolves through the same normalization.
        assertArrayEquals(byteArrayOf(1), donor.coverFor("Making Evenings", "Lake of Tears"))
    }

    @Test
    fun `artist mismatch answers no cover`() {
        val donor = AaCoverDonor()
        donor.offer("Song", "Artist A", byteArrayOf(1))
        assertNull(donor.coverFor("Song", "Artist B"))
    }

    @Test
    fun `latest offer wins`() {
        val donor = AaCoverDonor()
        donor.offer("Song", "Artist", byteArrayOf(1))
        donor.offer("Song", "Artist", byteArrayOf(2, 2))
        assertArrayEquals(byteArrayOf(2, 2), donor.coverFor("Song", "Artist"))
    }

    @Test
    fun `lru evicts past eight tracks`() {
        val donor = AaCoverDonor()
        for (i in 0 until 8) donor.offer("Track $i", "Artist", byteArrayOf(i.toByte()))
        donor.offer("Track 9", "Artist", byteArrayOf(9))
        assertNull(donor.coverFor("Track 0", "Artist"))
        assertArrayEquals(byteArrayOf(1), donor.coverFor("Track 1", "Artist"))
        // A read refreshes recency: the refreshed track survives the next eviction.
        donor.coverFor("Track 1", "Artist")
        donor.offer("Track 10", "Artist", byteArrayOf(10))
        assertArrayEquals(byteArrayOf(1), donor.coverFor("Track 1", "Artist"))
        assertNull(donor.coverFor("Track 2", "Artist"))
    }

    @Test
    fun `empty title or bytes are ignored`() {
        val donor = AaCoverDonor()
        donor.offer("", "Artist", byteArrayOf(1))
        donor.offer("Song", "Artist", ByteArray(0))
        donor.offer("Song", "Artist", null)
        assertNull(donor.coverFor("", "Artist"))
        assertNull(donor.coverFor("Song", "Artist"))
    }

    @Test
    fun `returned bytes are a copy`() {
        val donor = AaCoverDonor()
        val bytes = byteArrayOf(1, 2, 3)
        donor.offer("Song", "Artist", bytes)
        val out = donor.coverFor("Song", "Artist")!!
        out[0] = 9
        assertEquals(1.toByte(), donor.coverFor("Song", "Artist")!![0])
    }
}
