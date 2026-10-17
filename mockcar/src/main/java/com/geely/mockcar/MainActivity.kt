package com.geely.mockcar

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Minimal console: service status, the live property table, the two controls a debugging
 * round needs (start service / clear values) — plus the F014 media bench: the fake
 * instrument cluster (the received card with its cover drawn from the file:// uri, read
 * cross-uid exactly like the real cluster reads it) and the fake Bluetooth source buttons.
 * Built in code — a dev tool needs no layouts.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var tableView: TextView
    private lateinit var cardView: TextView
    private lateinit var cardReceipt: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var artworkView: ImageView
    private val state get() = MockCarStore.state
    private val dimState get() = DimMockStore
    private var running = false
    private var loadedArtwork: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            statusView.text = getString(R.string.status_running) +
                "\n" + getString(R.string.status_log_file, MockLog.currentFile?.absolutePath ?: "-")
            tableView.text = formatTable()
            refreshCard()
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

        // --- Fake instrument cluster (F014) ---
        column.addView(TextView(this).apply {
            text = getString(R.string.dim_section)
            textSize = 16f
            setPadding(0, padding, 0, padding / 2)
        })
        cardReceipt = TextView(this).apply { setTextIsSelectable(true) }
        column.addView(cardReceipt)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 200_000
        }
        column.addView(progressBar)
        cardView = TextView(this).apply { setTextIsSelectable(true) }
        column.addView(cardView)
        artworkView = ImageView(this).apply { minimumHeight = padding * 8 }
        column.addView(artworkView)
        column.addView(Button(this).apply {
            text = getString(R.string.dim_clear)
            setOnClickListener {
                dimState.clear()
                loadedArtwork = null
                artworkView.setImageDrawable(null)
                MockLog.i("UI: card cleared")
            }
        })

        // --- Fake Bluetooth source (F014) ---
        column.addView(TextView(this).apply {
            text = getString(R.string.bt_section)
            textSize = 16f
            setPadding(0, padding, 0, padding / 2)
        })
        button(column, getString(R.string.bt_play)) {
            ensureNotifications()
            startMockService(btIntent("com.geely.mockcar.BT_PLAY") {
                putExtra("title", "Mock Track")
                putExtra("artist", "Mock Artist")
                putExtra("artwork_delay_ms", 3_000L)
            })
        }
        button(column, getString(R.string.bt_pause)) { startMockService(btIntent("com.geely.mockcar.BT_PAUSE")) }
        button(column, getString(R.string.bt_stop)) { startMockService(btIntent("com.geely.mockcar.BT_STOP")) }
        column.addView(TextView(this).apply {
            text = getString(R.string.bt_hint)
            setPadding(0, padding / 2, 0, 0)
        })

        root.addView(column)
        setContentView(root)
    }

    private fun button(column: LinearLayout, text: String, onClick: () -> Unit) {
        column.addView(Button(this).apply { this.text = text; setOnClickListener { onClick() } })
    }

    private fun btIntent(action: String, configure: Intent.() -> Unit = {}): Intent =
        Intent(this, MockCarService::class.java).setAction(action).apply(configure)

    private fun startMockService(intent: Intent) {
        MockLog.start(applicationContext)
        startForegroundService(intent)
    }

    private fun ensureNotifications() {
        val wanted = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                wanted.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            // The cover lives in another app's MediaStore contribution — same as the real
            // cluster's privileged read, granted to this dev tool at runtime.
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                wanted.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        }
        if (wanted.isNotEmpty()) {
            requestPermissions(wanted.toTypedArray(), 1)
            Toast.makeText(this, R.string.bt_notifications_hint, Toast.LENGTH_LONG).show()
        }
    }

    /** The card as the cluster would see it; the cover is loaded from the file:// uri through
     *  this app's OWN uid — the cross-app read the real cluster performs. */
    private fun refreshCard() {
        cardReceipt.text = dimState.receiptLine
        cardView.text = dimState.lastCardFormatted
        val card = dimState.lastCard ?: run { progressBar.progress = 0; return }
        progressBar.max = card.getLong("getDuration", 200_000L).toInt().coerceAtLeast(1)
        progressBar.progress = dimState.progressMs.toInt()
        val uri = card.getString("getArtwork")
        if (uri == loadedArtwork) return
        loadedArtwork = uri
        if (uri.isNullOrEmpty()) {
            artworkView.setImageDrawable(null)
            return
        }
        val bitmap = runCatching {
            contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        artworkView.setImageBitmap(bitmap)
        if (bitmap == null) {
            MockLog.w("UI: artwork NOT readable cross-uid: $uri")
            Toast.makeText(this, R.string.dim_artwork_failed, Toast.LENGTH_SHORT).show()
        }
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
