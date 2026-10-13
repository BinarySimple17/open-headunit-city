package com.andrerinas.openheadunit.geely.hvac

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.utils.AppLog
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detailed event log of the climate panel: its own HVAC_Log_*.txt capture in the PUBLIC
 * Download/ directory — the one folder a stock file manager can always open.
 *
 * Why a separate capture: the point of this log is a "why does nothing happen" diagnosis made
 * after the fact (often with the car already parked), independent of the global log settings —
 * a user who never turns on log capture can still flip this one switch in the GEELY tab and
 * get the full reflection-init chain, every property read/write with property/area/value,
 * result and duration, panel show/hide reasons and stack traces.
 *
 * Why the public Download folder and nowhere else: the app's own log folder lives under
 * Android/data/, which ordinary file managers cannot open on Android 11+ — exactly the
 * firmware class these head units run, and the first on-HU round lost the capture to this.
 * The capture is a single file ([DownloadSink]): on API 29+ it is contributed through
 * MediaStore (an app may create and append to its own downloads with no permission at all),
 * on older builds it is written directly under the already-declared WRITE_EXTERNAL_STORAGE.
 * Old captures rotate away at the next start. When the file cannot be created the capture
 * degrades to "nothing written"; the reasons still reach AppLog/logcat unconditionally.
 */
object HvacLog {

    const val LOG_PREFIX = "HVAC_Log_"

    private const val TAG = "HVAC"

    /** Old captures beyond this are rotated away at the next start. */
    internal const val MAX_FILES = 10

    @Volatile private var sink: DownloadSink? = null

    val isEnabled: Boolean get() = sink != null

    /** Starts a fresh capture (rotating old ones first); stops any running one. */
    fun start(context: Context, model: Hvac.CarModel) {
        stop()
        val app = context.applicationContext
        val fileName = "$LOG_PREFIX${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.txt"
        val s = DownloadSink.create(app, fileName)
        sink = s
        if (s != null) {
            i("=== HVAC log start: app=${BuildConfig.VERSION_NAME} model=$model file=${s.description} ===")
        } else {
            // No file anywhere — at least leave the verdict where a logcat reader can find it.
            AppLog.e("[HVAC] detailed log NOT started: Download file unavailable (reason above)")
        }
    }

    /** Flushes and closes the capture. */
    fun stop() {
        sink?.close()
        sink = null
        AppLog.i("[HVAC] detailed log disabled")
    }

    fun d(msg: String) = println(Log.DEBUG, msg)

    fun i(msg: String) = println(Log.INFO, msg)

    fun w(msg: String) = println(Log.WARN, msg)

    fun e(msg: String, tr: Throwable? = null) {
        val trace = tr?.let { "\n${Log.getStackTraceString(it)}" } ?: ""
        sink?.println(Log.ERROR, TAG, "[HVAC] $msg$trace")
        // Errors always reach logcat even with the capture off — a silent panel must not be silent.
        if (tr != null) AppLog.e("[HVAC] $msg", tr) else AppLog.e("[HVAC] $msg")
    }

    private fun println(priority: Int, msg: String) {
        sink?.println(priority, TAG, "[HVAC] $msg")
    }

    /**
     * The Download/ capture. Writes are synchronous and flushed per line: panel traffic is a
     * few lines per interaction, and durability here matters more than throughput — this file
     * exists to survive the drive home and be pulled from the car.
     */
    private class DownloadSink private constructor(
        private val openStream: () -> OutputStream,
        val description: String,
    ) {

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        private val writer = BufferedWriter(OutputStreamWriter(openStream(), Charsets.UTF_8))
        private val lock = Any()

        fun println(priority: Int, tag: String, msg: String) {
            val level = when (priority) {
                Log.VERBOSE -> "V"
                Log.DEBUG -> "D"
                Log.INFO -> "I"
                Log.WARN -> "W"
                Log.ASSERT -> "A"
                else -> priority.toString()
            }
            val line = synchronized(dateFormat) { dateFormat.format(Date()) } + " [$tag:$level] $msg"
            try {
                synchronized(lock) {
                    writer.write(line)
                    writer.newLine()
                    writer.flush()
                }
            } catch (e: IOException) {
                AppLog.e("[HVAC] Download capture write failed: %s", e.toString())
            }
        }

        fun close() {
            synchronized(lock) {
                try { writer.flush() } catch (_: IOException) {}
                try { writer.close() } catch (_: IOException) {}
            }
        }

        companion object {
            /** Returns null when no Download file is possible; the reason reaches AppLog only. */
            fun create(context: Context, fileName: String): DownloadSink? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    mediaStore(context, fileName)
                } else {
                    legacy(context, fileName)
                }
            } catch (e: Throwable) {
                AppLog.e("[HVAC] Download capture unavailable: %s", e.toString())
                null
            }

            @RequiresApi(Build.VERSION_CODES.Q)
            private fun mediaStore(context: Context, fileName: String): DownloadSink {
                val resolver = context.contentResolver
                rotate(resolver)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("MediaStore insert returned null")
                val description = "${Environment.DIRECTORY_DOWNLOADS}/$fileName"
                val openStream = { resolver.openOutputStream(uri, "wa") ?: throw IOException("no stream for $uri") }
                return DownloadSink(openStream, description)
            }

            @RequiresApi(Build.VERSION_CODES.Q)
            private fun rotate(resolver: ContentResolver) {
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf("$LOG_PREFIX%"),
                    "${MediaStore.MediaColumns._ID} DESC",
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    var kept = 0
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        if (kept < MAX_FILES) {
                            kept++
                            continue
                        }
                        // Own downloads delete silently; someone else's same-named file just fails.
                        try {
                            resolver.delete(ContentUris.withAppendedId(collection, id), null, null)
                        } catch (_: Exception) {}
                    }
                }
            }

            private fun legacy(context: Context, fileName: String): DownloadSink? {
                val granted = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    AppLog.e("[HVAC] Download capture unavailable: WRITE_EXTERNAL_STORAGE not granted (API < 29)")
                    return null
                }
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!(dir.exists() || dir.mkdirs())) throw IOException("cannot create ${dir.absolutePath}")
                val files = dir.listFiles { _, name -> name.startsWith(LOG_PREFIX) }
                    ?.sortedBy { it.lastModified() } ?: emptyList()
                files.take((files.size - MAX_FILES).coerceAtLeast(0)).forEach { it.delete() }
                val description = "${Environment.DIRECTORY_DOWNLOADS}/$fileName"
                val openStream = { FileOutputStream(File(dir, fileName), true) as OutputStream }
                return DownloadSink(openStream, description)
            }
        }
    }
}
