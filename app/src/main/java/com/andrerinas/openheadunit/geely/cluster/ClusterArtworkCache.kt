package com.andrerinas.openheadunit.geely.cluster

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.geely.hvac.HvacLog
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * The cover side of the cluster card (F013). The cluster reads artwork as a file path: the
 * reference normalizes every artwork source into a file in a PUBLIC folder and hands out
 * Uri.fromFile, because the system cluster process reads cross-application through the plain
 * filesystem, not through our ContentProviders (конспект §2c).
 *
 * The catalog is the public Pictures/ohu-media/: contributed through MediaStore on API 29+
 * (an app may create its own pictures with no permission at all, and the physical path comes
 * back through the DATA column), written directly below that on older builds — the same
 * split HvacLog uses for Download, backed by the already-declared WRITE_EXTERNAL_STORAGE.
 * Files are named by the track's hash, so a repeated track reuses its file, and the LRU
 * keeps at most [MAX_FILES] of them.
 */
class ClusterArtworkCache(private val context: Context) {

    /** Returns a file:// uri string for the bytes, or null when nothing could be stored. */
    fun store(trackKey: String, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val name = fileNameFor(trackKey, sniffImageType(bytes))
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) storeViaMediaStore(name, bytes)
            else storeDirect(name, bytes)
        } catch (e: Throwable) {
            HvacLog.e("cluster.art: store failed for $name", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun storeViaMediaStore(name: String, bytes: ByteArray): String? {
        val resolver = context.contentResolver
        // Hash-named: an existing file for this track is already the cover we would write.
        queryDataPath(resolver, name)?.let { return Uri.fromFile(File(it)).toString() }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, PUBLIC_SUBDIR)
        }
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("MediaStore insert returned null")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw java.io.IOException("no stream for $uri")
        rotate(resolver)
        // The cluster needs the physical path; DATA is deprecated but still the way back.
        val path = queryDataPath(resolver, name)
        return path?.let { Uri.fromFile(File(it)).toString() }
    }

    private fun storeDirect(name: String, bytes: ByteArray): String? {
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            HvacLog.e("cluster.art: WRITE_EXTERNAL_STORAGE not granted (API < 29) — no covers")
            return null
        }
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            LOCAL_SUBDIR,
        )
        if (!(dir.exists() || dir.mkdirs())) throw java.io.IOException("cannot create ${dir.absolutePath}")
        val legacy = dir.listFiles { _, n -> n.startsWith(NAME_PREFIX) }?.map { FileEntry(it.name, it.lastModified()) }
        legacy?.let { dropBeyondLimit(it, MAX_FILES).forEach { n -> File(dir, n).delete() } }
        val out = File(dir, name)
        FileOutputStream(out).use { it.write(bytes) }
        return Uri.fromFile(out).toString()
    }

    private fun queryDataPath(resolver: android.content.ContentResolver, name: String): String? {
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DATA),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(name, "$PUBLIC_SUBDIR/"),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val path = cursor.getString(0)
                if (!path.isNullOrEmpty() && File(path).exists()) return path
            }
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun rotate(resolver: android.content.ContentResolver) {
        val entries = mutableListOf<FileEntry>()
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("$PUBLIC_SUBDIR/", "$NAME_PREFIX%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val added = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext()) entries.add(FileEntry(cursor.getLong(id).toString(), cursor.getLong(added)))
        }
        // Own contributions delete silently; someone else's same-named file just fails.
        dropBeyondLimit(entries, MAX_FILES).forEach { key ->
            try {
                resolver.delete(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, key.toLong()), null, null)
            } catch (_: Exception) {}
        }
    }

    /** One cached cover as the rotation sees it: a deletable key and its recency stamp. */
    internal data class FileEntry(val key: String, val stamp: Long)

    internal companion object {
        /** MediaStore RELATIVE_PATH of the catalog, trailing-slash free. */
        private const val PUBLIC_SUBDIR = "Pictures/ohu-media"
        /** Directory below the public Pictures root on legacy (pre-Q) builds. */
        private const val LOCAL_SUBDIR = "ohu-media"
        private const val NAME_PREFIX = "ohu_media_"

        /** Covers are small; a couple dozen recent tracks is plenty for a drive. */
        internal const val MAX_FILES = 24

        /** Hash-named, so the same track keeps one file no matter how often it plays. */
        internal fun fileNameFor(trackKey: String, type: String): String =
            "$NAME_PREFIX${sha256Hex(trackKey).take(16)}.$type"

        internal fun dropBeyondLimit(entries: List<FileEntry>, limit: Int): List<String> =
            entries.sortedByDescending { it.stamp }.drop(limit).map { it.key }

        internal fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/** Picture type by magic bytes — the extension and MIME follow it, bytes are stored as-is. */
internal fun sniffImageType(bytes: ByteArray): String = when {
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpg"
    bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "png"
    else -> "img"
}

internal fun mimeOf(fileName: String): String = when (fileName.substringAfterLast('.')) {
    "png" -> "image/png"
    else -> "image/jpeg"
}
