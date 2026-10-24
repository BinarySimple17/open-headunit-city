package com.andrerinas.openheadunit.geely.cluster

import android.content.Context
import android.os.Build
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.geely.hvac.HvacLog
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.LogExporter
import com.andrerinas.openheadunit.utils.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The planner's desk has no adb into the head unit, so the one measurement the flicker
 * hunt still misses — what the SYSTEM publishers log on their side of the cluster card
 * fight (BL-004 fact 32: every HUR dump so far held a single pid, our own) ships as a
 * settings button: run logcat and the media-session dump ON the unit, hand the file to
 * the same Bluetooth share route every other log takes.
 *
 * Privilege decides how much is visible — an unprivileged logcat sees only this app's
 * process, root sees the whole system (the stock media publisher's updatePlaybackInfo
 * lines included). Both lanes run the SAME script; the header names the lane, so a
 * partial capture can never be misread as a complete one. (Shizuku cannot run commands
 * without a bound UserService, so it is not a lane here.)
 */
object ClusterDiagnosticsCapture {

    /** Tags the stock publishers are known or suspected to log under. The raw-tail
     *  section of the script is the safety net for every tag not on this list. */
    internal val MEDIA_TAGS = listOf(
        "AdapterSrv", "AdapterSrv.EcarxMediaInteractionImpl", "EcarxMediaInteractionImpl",
        "MediaInteraction", "DimInteraction", "MediaCenter", "EcarxCarPort",
        "MediaSessionService", "avrcp", "btservice",
    )

    /** How much raw logcat rides along unfiltered — big enough to hold the last
     *  arbitration episodes, small enough for a Bluetooth share. */
    internal const val RAW_TAIL_LINES = 3000

    private const val READ_TIMEOUT_MS = 20_000L
    private const val MAX_BYTES = 4 * 1024 * 1024

    /** Which execution lane the script ran in; the label lands in the file header. */
    internal enum class Privilege(internal val label: String) {
        ROOT("root"), NONE("unprivileged")
    }

    /** The one script every mode runs — POSIX sh, no toolbox assumptions, each section
     *  delimited so a reader can tell what succeeded even when a section comes back empty. */
    internal fun script(): String = buildString {
        append("echo '=== section: media logcat (tag filter) ==='\n")
        append("logcat -d -v time -s ").append(MEDIA_TAGS.joinToString(" ")).append('\n')
        append("echo '=== section: raw logcat tail (last ").append(RAW_TAIL_LINES).append(" lines) ==='\n")
        append("logcat -d -v time -t ").append(RAW_TAIL_LINES).append('\n')
        append("echo '=== section: media session stack ==='\n")
        append("dumpsys media_session\n")
        append("echo '=== capture end ==='\n")
    }

    /** The report header; the unprivileged note exists so a partial capture cannot be
     *  mistaken for the system-wide one it was taken for. Pure — unit-tested. */
    internal fun header(
        mode: Privilege,
        versionName: String,
        device: String,
        api: Int,
        at: Date,
    ): String = buildString {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(at)
        append("=== cluster diagnostics $stamp ===\n")
        append("app=$versionName device=$device api=$api privilege=${mode.label}\n")
        if (mode == Privilege.NONE) {
            append("NOTE: no root on this unit — an unprivileged logcat sees only this\n")
            append("app's own process; the stock publishers' lines are NOT here.\n")
        }
        append('\n')
    }

    /** One file per second is enough — the button is pressed by hand. Pure — unit-tested. */
    internal fun fileName(at: Date): String =
        "cluster_diag_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(at)}.txt"

    /** Captures the report and opens the share sheet; every failure degrades to a toast. */
    suspend fun captureAndShare(context: Context) {
        val app = context.applicationContext
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val (output, mode) = runWithBestPrivilege()
                writeReport(app, output, mode) to mode
            }
        }
        result.fold(
            onSuccess = { (file, mode) ->
                HvacLog.i("cluster.diag: captured via ${mode.label} -> ${file.name} (${file.length()} B)")
                AppLog.i("[HVAC] cluster.diag: captured via ${mode.label} -> ${file.absolutePath}")
                ToastUtils.showToast(
                    app,
                    app.getString(R.string.cluster_diag_captured, modeLabel(app, mode)),
                    android.widget.Toast.LENGTH_LONG,
                    force = true,
                )
                LogExporter.shareLogFile(app, file)
            },
            onFailure = { failure ->
                HvacLog.e("cluster.diag: capture failed", failure)
                AppLog.e("[HVAC] cluster.diag: capture failed: %s", failure.toString())
                ToastUtils.showToast(
                    app,
                    app.getString(
                        R.string.cluster_diag_failed,
                        failure.message ?: failure.javaClass.simpleName,
                    ),
                    android.widget.Toast.LENGTH_LONG,
                    force = true,
                )
            },
        )
    }

    private fun modeLabel(app: Context, mode: Privilege): String = when (mode) {
        Privilege.ROOT -> app.getString(R.string.cluster_diag_mode_root)
        Privilege.NONE -> app.getString(R.string.cluster_diag_mode_none)
    }

    /** Root first (the whole-system view), the unprivileged lane always available. */
    private fun runWithBestPrivilege(): Pair<String, Privilege> =
        tryRoot() ?: runPlain()

    private fun tryRoot(): Pair<String, Privilege>? = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", script()))
        readProcess(process)?.let { it to Privilege.ROOT }
    } catch (_: Exception) {
        null
    }

    private fun runPlain(): Pair<String, Privilege> = try {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", script()))
        (readProcess(process) ?: "") to Privilege.NONE
    } catch (_: Exception) {
        "" to Privilege.NONE
    }

    /**
     * Reads a finished process or gives up on the watchdog. Null means "the script never
     * ran" — a denied su answers with an error text and none of the section markers — so
     * the caller can fall through to the next privilege lane instead of shipping a file
     * that looks captured but holds nothing.
     */
    private fun readProcess(process: Process): String? {
        val stderr = ByteArrayOutputStream()
        val drain = Thread {
            runCatching {
                process.errorStream.copyTo(stderr, 64 * 1024)
            }
        }.apply { isDaemon = true; start() }
        val watchdog = Thread {
            runCatching { Thread.sleep(READ_TIMEOUT_MS) }
            process.destroy()
        }.apply { isDaemon = true; start() }
        return try {
            val stdout = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            process.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    stdout.write(buffer, 0, read)
                    if (stdout.size() > MAX_BYTES) {
                        process.destroy()
                        break
                    }
                }
            }
            runCatching { process.waitFor() }
            val text = buildString {
                append(stdout.toString(Charsets.UTF_8.name()))
                val err = stderr.toString(Charsets.UTF_8.name()).trim()
                if (err.isNotEmpty()) append("\n[stderr]\n").append(err).append('\n')
            }
            if ("=== section: media logcat" !in text) null else text
        } catch (_: Exception) {
            null
        } finally {
            watchdog.interrupt()
        }
    }

    private fun writeReport(app: Context, output: String, mode: Privilege): File {
        val dir = File(app.getExternalFilesDir(null), "diag").apply { mkdirs() }
        val file = File(dir, fileName(Date()))
        val head = header(
            mode,
            BuildConfig.VERSION_NAME,
            "${Build.MANUFACTURER} ${Build.MODEL}",
            Build.VERSION.SDK_INT,
            Date(),
        )
        file.writeText(head + output)
        return file
    }
}
