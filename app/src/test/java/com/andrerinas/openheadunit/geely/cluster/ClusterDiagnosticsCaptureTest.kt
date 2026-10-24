package com.andrerinas.openheadunit.geely.cluster

import com.andrerinas.openheadunit.geely.cluster.ClusterDiagnosticsCapture.Privilege
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The pure halves of the cluster-diagnostics button (S015, fact 32): the script every
 * privilege lane runs, the header that names its lane, and the file name. The script's
 * shape is the contract — a missing section or tag would silently shrink what the
 * planner can see from a capture that still looks successful.
 */
class ClusterDiagnosticsCaptureTest {

    // Parse and format both run in the JVM's default zone, so the round trip through
    // fileName() agrees regardless of where the test machine sits.
    private fun date(text: String): Date =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(text)!!

    @Test
    fun `the script carries both logcat passes and the session dump`() {
        val script = ClusterDiagnosticsCapture.script()
        assertTrue("filtered logcat pass present", script.contains("logcat -d -v time -s "))
        ClusterDiagnosticsCapture.MEDIA_TAGS.forEach { tag ->
            assertTrue("tag $tag in the filter", script.contains(tag))
        }
        assertTrue(
            "raw tail pass present",
            script.contains("logcat -d -v time -t ${ClusterDiagnosticsCapture.RAW_TAIL_LINES}"),
        )
        assertTrue("media session dump present", script.contains("dumpsys media_session"))
        assertTrue("end marker present", script.contains("=== capture end ==="))
    }

    @Test
    fun `an unprivileged header says so - a partial capture cannot be misread as complete`() {
        val plain = ClusterDiagnosticsCapture.header(
            Privilege.NONE, "3.5.0-beta28", "QTI G426_J1", 30, date("2026-10-24 21:30:00"),
        )
        assertTrue(plain.contains("privilege=unprivileged"))
        assertTrue("the system-lines warning must be there", plain.contains("NOT here"))
        assertTrue(plain.contains("app=3.5.0-beta28"))
        assertTrue(plain.contains("device=QTI G426_J1"))
        assertTrue(plain.contains("api=30"))

        val rooted = ClusterDiagnosticsCapture.header(
            Privilege.ROOT, "3.5.0-beta28", "QTI G426_J1", 30, date("2026-10-24 21:30:00"),
        )
        assertTrue(rooted.contains("privilege=root"))
        assertFalse("no warning on a privileged capture", rooted.contains("NOT here"))
    }

    @Test
    fun `file names are timestamped and carry no directories`() {
        assertEquals(
            "cluster_diag_20261024_213000.txt",
            ClusterDiagnosticsCapture.fileName(date("2026-10-24 21:30:00")),
        )
    }
}
