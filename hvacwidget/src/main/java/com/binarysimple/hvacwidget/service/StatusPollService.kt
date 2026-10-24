package com.binarysimple.hvacwidget.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.binarysimple.hvacwidget.ClimateWidgetProvider
import com.binarysimple.hvacwidget.R
import com.binarysimple.hvacwidget.WidgetLog
import com.binarysimple.hvacwidget.WidgetStatus
import com.binarysimple.hvacwidget.car.EcarxWidgetPort
import com.binarysimple.hvacwidget.functions.WidgetFunctions
import com.binarysimple.hvacwidget.functions.WidgetSettings

/**
 * Keeps widget statuses alive: a foreground service (connectedDevice — the Cityray reference
 * pattern, docs/hvac-widget-app.md 3) polling every function of the registry on a worker
 * thread every [POLL_INTERVAL_MS], pushing a re-render only on a diff. Reads are quiet;
 * the port connects here on the worker, off the main thread.
 */
class StatusPollService : Service() {

    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        worker = HandlerThread("hvacwidget-poll").apply { start() }
        handler = Handler(worker.looper)
        handler.post { poll() }
        WidgetLog.i("poll service started (interval=${POLL_INTERVAL_MS} ms)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (this::handler.isInitialized) handler.removeCallbacksAndMessages(null)
        if (this::worker.isInitialized) worker.quitSafely()
        WidgetLog.i("poll service stopped")
        super.onDestroy()
    }

    private fun poll() {
        val context = applicationContext
        try {
            if (!EcarxWidgetPort.available && !EcarxWidgetPort.connect(context)) {
                // No car service on this build (or the firmware declined): stay quiet, retry
                // next tick — degradation, never a crash (canon rule 8).
            } else {
                val model = WidgetSettings(context).carModel
                val next = LinkedHashMap<String, Int?>()
                for (spec in WidgetFunctions.all(model)) {
                    next[spec.id] = EcarxWidgetPort.getIntQuiet(spec.propertyId, spec.areaId)
                }
                val changed = next != WidgetStatus.snapshot
                WidgetStatus.replace(next)
                if (changed) ClimateWidgetProvider.pushAll(context)
            }
        } catch (e: Throwable) {
            WidgetLog.e("poll failed", e)
        }
        handler.postDelayed({ poll() }, POLL_INTERVAL_MS)
    }

    private fun startInForeground() {
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_MIN),
            )
            val notification: Notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_widget)
                .setContentTitle(getString(R.string.notification_title))
                .setOngoing(true)
                .build()
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Throwable) {
            // Degrade, never crash-loop: a service that cannot promote itself stops quietly
            // (well inside the startForegroundService contract) instead of dying repeatedly.
            WidgetLog.e("foreground promotion failed — stopping the poll service", e)
            stopSelf()
        }
    }

    companion object {
        private const val CHANNEL_ID = "status"
        private const val NOTIFICATION_ID = 1
        const val POLL_INTERVAL_MS = 5000L

        /**
         * Widget updates and boot arrive in the background, where newer Androids restrict
         * foreground starts — a rejection degrades to "no live poll" (the widget keeps its
         * last render and tap handling), never crashes.
         */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, StatusPollService::class.java))
            } catch (e: Throwable) {
                WidgetLog.e("foreground start rejected — running without live poll", e)
            }
        }
    }
}
