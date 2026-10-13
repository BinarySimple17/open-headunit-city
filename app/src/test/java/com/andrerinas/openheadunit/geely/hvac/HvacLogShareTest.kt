package com.andrerinas.openheadunit.geely.hvac

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the CAR-tab "send logs" button hands to the share sheet: only this capture's files,
 * newest first (the round being reported is the one a triage reads first), capped at the
 * retention limit the rotation itself keeps.
 */
class HvacLogShareTest {

    private val limit = HvacLog.MAX_FILES

    @Test
    fun `keeps only hvac captures, newest first`() {
        val names = listOf(
            "HVAC_Log_20261012_204508_763.txt",
            "SomeOther_Log_20261012_204508_763.txt",
            "HVAC_Log_20261013_091500_123.txt",
            "hvac_log_lowercase.txt",
        )
        assertEquals(
            listOf(
                "HVAC_Log_20261013_091500_123.txt",
                "HVAC_Log_20261012_204508_763.txt",
            ),
            HvacLogShare.retainedCaptureNames(names, limit)
        )
    }

    @Test
    fun `caps at the retention limit, keeping the newest`() {
        val names = (1..(limit + 3)).map {
            "HVAC_Log_20261013_0000%02d_000.txt".format(it)
        }
        val retained = HvacLogShare.retainedCaptureNames(names, limit)
        assertEquals(limit, retained.size)
        assertEquals("HVAC_Log_20261013_0000%02d_000.txt".format(limit + 3), retained.first())
    }

    @Test
    fun `fixed-width timestamps make name order chronological`() {
        val names = listOf(
            "HVAC_Log_20261012_204508_763.txt",
            "HVAC_Log_20261013_091500_123.txt",
            "HVAC_Log_20261101_000000_001.txt",
        )
        assertEquals(names.asReversed(), HvacLogShare.retainedCaptureNames(names, limit))
    }

    @Test
    fun `nothing captured - empty share`() {
        assertEquals(emptyList<String>(), HvacLogShare.retainedCaptureNames(emptyList(), limit))
    }
}
