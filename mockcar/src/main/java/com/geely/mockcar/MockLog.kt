package com.geely.mockcar

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The mock's own event log: every property call goes to logcat (tag MOCKCAR) and to a
 * MockCar_Log_*.txt capture in the app's external files directory, so a debugging round can
 * be reconstructed after the fact ("что панель отправляла машине") without a live adb session.
 *
 * Deliberately tiny and standalone (this module does not share code with :app): a bounded
 * last-N rotation, one background-free synchronized writer — call volume here is human-scale.
 */
object MockLog {

    private const val TAG = "MOCKCAR"
    private const val PREFIX = "MockCar_Log_"
    private const val MAX_FILES = 5

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var writer: BufferedWriter? = null
    private var file: File? = null

    val currentFile: File? get() = file

    @Synchronized
    fun start(context: Context) {
        if (writer != null) return
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        rotate(dir)
        val target = File(dir, "$PREFIX${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.txt")
        try {
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(target, true), Charsets.UTF_8))
            file = target
            Log.i(TAG, "log file started: ${target.absolutePath}")
        } catch (e: IOException) {
            Log.e(TAG, "cannot open log file", e)
        }
    }

    @Synchronized
    fun stop() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: IOException) {
        }
        writer = null
        file = null
    }

    fun i(msg: String) = println(Log.INFO, msg)

    fun w(msg: String) = println(Log.WARN, msg)

    @Synchronized
    private fun println(priority: Int, msg: String) {
        val level = when (priority) {
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> "I"
        }
        Log.println(priority, TAG, msg)
        val w = writer ?: return
        try {
            w.write("${dateFormat.format(Date())} [$level] $msg")
            w.newLine()
            w.flush()
        } catch (_: IOException) {
        }
    }

    /** Keeps the newest [MAX_FILES] captures, deletes older ones. */
    private fun rotate(dir: File) {
        dir.listFiles { _, name -> name.startsWith(PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_FILES)
            ?.forEach { it.delete() }
    }
}
