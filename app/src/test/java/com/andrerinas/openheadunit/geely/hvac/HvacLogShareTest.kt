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

    /**
     * The share sheet reads the URIs this app remembered at insert time (the firmware's name
     * query stopped answering even for a file inserted a minute earlier), so the memory has
     * to hold the newest captures in insertion order and never grow past the retention limit.
     */
    @Test
    fun `remembered uris keep insertion order, capped at the retention limit`() {
        val first = "content://media/external/downloads/101"
        val second = "content://media/external/downloads/102"

        val one = HvacLog.rememberUriString(null, first, limit)
        assertEquals(first, one)

        val two = HvacLog.rememberUriString(one, second, limit)
        assertEquals("$second\n$first", two)

        // Re-sharing an already remembered URI must not duplicate it.
        assertEquals(two, HvacLog.rememberUriString(two, second, limit))

        val many = (1..(limit + 2)).joinToString("\n") {
            "content://media/external/downloads/$it"
        }
        val next = HvacLog.rememberUriString(many, "content://media/external/downloads/999", limit)
        val lines = next.split('\n')
        assertEquals(limit, lines.size)
        assertEquals("content://media/external/downloads/999", lines.first())
    }

    @Test
    fun `blank and null memories are tolerated`() {
        assertEquals("u1", HvacLog.rememberUriString(null, "u1", limit))
        assertEquals("u1", HvacLog.rememberUriString("", "u1", limit))
        assertEquals("u2\nu1", HvacLog.rememberUriString("u1\n\n \n", "u2", limit))
    }
}
