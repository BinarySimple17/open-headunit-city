package com.andrerinas.openheadunit.geely.cluster

import android.media.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The track key of the S015 Task 4 (BL-004 fact 10): title|artist|album ONLY. This unit
 * changed a numeric metadata field every tick — with duration in the key each read re-issued
 * the uuid and re-ran the donor and the jpeg encode at 1 Hz for nothing. The two "reads" are
 * deliberately different by DURATION: a regression that puts it back turns this red, and the
 * describeKeys case pins the numeric VALUE print the same round asked for.
 */
class BtMediaMonitorKeyTest {

    @Test
    fun `duration jitter no longer re-issues the key`() {
        assertEquals(
            BtMediaMonitor.trackKeyOf(meta(durationMs = 210_000L)),
            BtMediaMonitor.trackKeyOf(meta(durationMs = 210_913L)),
        )
    }

    @Test
    fun `key is exactly title-artist-album`() {
        assertEquals(
            "Follow the Lie|Lake of Tears|Illwill",
            BtMediaMonitor.trackKeyOf("Follow the Lie", "Lake of Tears", "Illwill"),
        )
    }

    @Test
    fun `missing strings collapse to empty parts`() {
        val bare: MediaMetadata = mock()
        whenever(bare.getLong(MediaMetadata.METADATA_KEY_DURATION)).thenReturn(42L)
        assertEquals("||", BtMediaMonitor.trackKeyOf(bare))
    }

    @Test
    fun `describeKeys prints numeric values`() {
        val keys: MediaMetadata = mock()
        whenever(keys.keySet()).thenReturn(setOf(MediaMetadata.METADATA_KEY_DURATION))
        whenever(keys.getLong(MediaMetadata.METADATA_KEY_DURATION)).thenReturn(210_000L)
        val described = BtSessionDiagnostics.describeKeys(keys)
        assertTrue("numeric value printed, was: $described", described.contains("DURATION=n210000"))
    }

    private fun meta(durationMs: Long): MediaMetadata = mock<MediaMetadata>().also { m ->
        whenever(m.getString(MediaMetadata.METADATA_KEY_TITLE)).thenReturn("Follow the Lie")
        whenever(m.getString(MediaMetadata.METADATA_KEY_ARTIST)).thenReturn("Lake of Tears")
        whenever(m.getString(MediaMetadata.METADATA_KEY_ALBUM)).thenReturn("Illwill")
        whenever(m.getLong(MediaMetadata.METADATA_KEY_DURATION)).thenReturn(durationMs)
    }
}
