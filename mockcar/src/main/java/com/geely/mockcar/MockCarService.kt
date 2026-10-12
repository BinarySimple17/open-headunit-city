package com.geely.mockcar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * The stand-in for the Geely/ECARX car service (F006): an exported AIDL service with the
 * getFunctionValue/setFunctionValue surface, in-memory property state, per-call logging to
 * logcat + MockCar_Log_*.txt, and an adb-driven debug action so the log can be exercised
 * without the headunit panel:
 *
 *   adb shell am start-foreground-service -n com.geely.mockcar/.MockCarService -a com.geely.mockcar.SET \
 *       --ei id 268763648 --ei area 1 --ei value 268763649
 */
class MockCarService : Service() {

    private val state get() = MockCarStore.state

    private val binder = object : ICarFunctionMock.Stub() {
        override fun getFunctionValue(propertyId: Int, areaId: Int): Int {
            val value = state.get(propertyId, areaId)
            MockLog.i("get(propertyId=$propertyId, area=$areaId) = $value")
            return value
        }

        override fun setFunctionValue(propertyId: Int, areaId: Int, value: Int): Boolean {
            val accepted = state.set(propertyId, areaId, value)
            val verdict = if (accepted) "OK" else "REJECTED"
            MockLog.i("set(propertyId=$propertyId, area=$areaId, value=$value) -> $verdict")
            return accepted
        }

        override fun isAlive(): Boolean {
            MockLog.i("isAlive() = true")
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        MockLog.start(this)
        MockLog.i("service created, pid=${android.os.Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_SET -> {
                val id = intent.getIntExtra(EXTRA_ID, 0)
                val area = intent.getIntExtra(EXTRA_AREA, Int.MIN_VALUE)
                val value = intent.getIntExtra(EXTRA_VALUE, 0)
                MockLog.i("adb SET requested: set(propertyId=$id, area=$area, value=$value)")
                binder.setFunctionValue(id, area, value)
            }

            else -> MockLog.i("started (action=${intent?.action ?: "none"})")
        }
        // Keep running until explicitly stopped; the mock is expected to just exist.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        val caller = packageManager.getNameForUid(Binder.getCallingUid()) ?: "?"
        MockLog.i("bound by $caller")
        return binder
    }

    override fun onDestroy() {
        MockLog.i("service destroyed")
        MockLog.stop()
        super.onDestroy()
    }

    private fun goForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mock car service", NotificationManager.IMPORTANCE_LOW)
            )
            startForeground(NOTIFICATION_ID, buildNotification())
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "mockcar"
        const val NOTIFICATION_ID = 1
        const val ACTION_SET = "com.geely.mockcar.SET"
        const val EXTRA_ID = "id"
        const val EXTRA_AREA = "area"
        const val EXTRA_VALUE = "value"
    }
}
