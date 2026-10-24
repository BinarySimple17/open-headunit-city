package com.andrerinas.openheadunit.geely.hvac

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hands the retained HVAC captures to the system share sheet — the same route the app's own
 * logs take ([com.andrerinas.openheadunit.utils.LogExporter.shareLogFile]): a chooser where
 * the reporter picks Bluetooth and the files land on a phone without any cable.
 *
 * On these head units this is the difference between a log that reaches triage and one that
 * stays in the car: Download/ is reachable on the unit only through a file-manager dance,
 * and the HVAC log exists precisely for the "why did nothing happen" diagnosis made after
 * the drive. All retained captures go out, newest first ([HvacLog.MAX_FILES] is the
 * retention limit — sharing more than retention keeps would be a lie about what exists).
 *
 * Shares URIs, not paths: API 29+ captures live in MediaStore and share by their content
 * URI, legacy ones sit in the public Download directory behind the FileProvider root that
 * already covers it (the same root the app-log share uses below Android 10). The capture
 * currently being written goes out as a snapshot — every chooser target reads it that way.
 */
object HvacLogShare {

    /**
     * Retained captures by file name, newest first, capped at [limit]. The timestamp inside
     * the name is fixed-width, so lexicographic order is chronological. Pure — unit-tested.
     */
    internal fun retainedCaptureNames(names: Collection<String>, limit: Int): List<String> =
        names.filter { it.startsWith(HvacLog.LOG_PREFIX) }.sortedDescending().take(limit)

    /** Collects the retained captures and opens the share sheet; nothing to share — a hint. */
    suspend fun share(context: Context) {
        val app = context.applicationContext
        val uris = withContext(Dispatchers.IO) { collectUris(app) }
        if (uris.isEmpty()) {
            ToastUtils.showToast(
                app,
                app.getString(R.string.hvac_send_logs_none),
                Toast.LENGTH_LONG,
                force = true
            )
            return
        }

        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, app.getString(R.string.hvac_send_logs))
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(chooser)
    }

    /** Every step guarded: this runs from a settings click and must degrade, not throw. */
    private fun collectUris(context: Context): List<Uri> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mediaStoreUris(context)
        } else {
            legacyUris(context)
        }
    } catch (e: Throwable) {
        AppLog.e("[HVAC] share: cannot collect log files: %s", e.toString())
        emptyList()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreUris(context: Context): List<Uri> {
        // Own-URI memory first: the firmware's provider stopped answering the name query even
        // for a file this app inserted a minute earlier (2026-10-24 round), so a query-only
        // collector would say "no logs" while the capture it just wrote is still open.
        val remembered = HvacLog.rememberedCaptureUris(context)
        if (remembered.isNotEmpty()) return remembered
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val names = ArrayList<String>()
        val urisByName = HashMap<String, Uri>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("${HvacLog.LOG_PREFIX}%"),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                names.add(name)
                urisByName[name] = ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
        }
        return retainedCaptureNames(names, HvacLog.MAX_FILES).mapNotNull(urisByName::get)
    }

    private fun legacyUris(context: Context): List<Uri> {
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val byName = dir.listFiles { _, name -> name.startsWith(HvacLog.LOG_PREFIX) }
            ?.associateBy { it.name } ?: emptyMap()
        return retainedCaptureNames(byName.keys, HvacLog.MAX_FILES).mapNotNull { name ->
            byName[name]?.takeIf { it.isFile }?.let {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
            }
        }
    }
}
