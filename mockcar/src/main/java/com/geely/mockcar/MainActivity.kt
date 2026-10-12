package com.geely.mockcar

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Minimal console: service status, the live property table, and the two controls a debugging
 * round needs (start service / clear values). Built in code — a dev tool needs no layouts.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var tableView: TextView
    private val state get() = MockCarStore.state
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            statusView.text = getString(R.string.status_running) +
                "\n" + getString(R.string.status_log_file, MockLog.currentFile?.absolutePath ?: "-")
            tableView.text = formatTable()
            statusView.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val root = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        statusView = TextView(this)
        tableView = TextView(this).apply { setTextIsSelectable(true) }
        column.addView(statusView)
        column.addView(Button(this).apply {
            text = getString(R.string.start_service)
            setOnClickListener {
                running = true
                MockLog.start(applicationContext)
                startForegroundService(Intent(applicationContext, MockCarService::class.java))
            }
        })
        column.addView(Button(this).apply {
            text = getString(R.string.clear_values)
            setOnClickListener {
                state.clear()
                MockLog.i("UI: values cleared")
            }
        })
        column.addView(tableView)
        root.addView(column)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post(ticker)
    }

    override fun onPause() {
        window.decorView.removeCallbacks(ticker)
        super.onPause()
    }

    private fun formatTable(): String {
        val rows = state.snapshot()
        if (rows.isEmpty()) return getString(R.string.table_empty)
        return rows.entries
            .sortedBy { it.key }
            .joinToString("\n") { (key, value) ->
                val id = (key ushr 32).toInt()
                val area = key.toInt()
                "prop=$id area=$area -> $value"
            }
    }
}
