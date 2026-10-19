package com.geely.mockcar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
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
 *
 * Media-card stand-in (F014): the same service also exports [IDimMediaMock] — bound with the
 * [ACTION_DIM_BIND] action (a plain bind without the action gets the car-function binder) —
 * accepting the headunit fork's cluster cards, logging every call, and showing the card in
 * the console as a fake instrument cluster. The fake Bluetooth source is driven from the
 * console or via adb:
 *
 *   adb shell am start-foreground-service -n com.geely.mockcar/.MockCarService -a com.geely.mockcar.BT_PLAY \
 *       --es title "Track" --es artist "Artist" --el artwork_delay_ms 3000
 *   ... -a com.geely.mockcar.BT_PAUSE   /   ... -a com.geely.mockcar.BT_STOP
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

    private val dimBinder = object : IDimMediaMock.Stub() {
        override fun updatePlaybackInfo(card: Bundle?): Boolean {
            if (card == null) return false
            DimMockStore.acceptCard(card)
            val title = card.getString("getTitle")
            val verdict = if (title.isNullOrEmpty()) "CLEAR" else "OK"
            MockLog.i(
                "dim: updatePlaybackInfo -> $verdict (title=${card.getString("getTitle")}, " +
                    "artwork=${card.getString("getArtwork")}, status=${card.getInt("getPlaybackStatus")}, " +
                    "duration=${card.getLong("getDuration")}, sourceType=${card.getInt("getSourceType")})"
            )
            return true
        }

        override fun updateCurrentProgress(positionMs: Long) {
            DimMockStore.acceptProgress(positionMs)
            MockLog.i("dim: updateCurrentProgress($positionMs)")
        }

        override fun isAlive(): Boolean = true
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

            ACTION_BT_PLAY -> FakeBtSource.play(
                this,
                intent.getStringExtra(EXTRA_TITLE) ?: "Mock Track",
                intent.getStringExtra(EXTRA_ARTIST) ?: "Mock Artist",
                intent.getLongExtra(EXTRA_ARTWORK_DELAY_MS, 3_000L),
            )

            ACTION_BT_PAUSE -> FakeBtSource.pause(this)
            ACTION_BT_STOP -> FakeBtSource.stop(this)

            else -> MockLog.i("started (action=${intent?.action ?: "none"})")
        }
        // Keep running until explicitly stopped; the mock is expected to just exist.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        val caller = packageManager.getNameForUid(Binder.getCallingUid()) ?: "?"
        if (intent?.action == ACTION_DIM_BIND) {
            MockLog.i("bound by $caller (media card stand-in)")
            return dimBinder
        }
        MockLog.i("bound by $caller")
        return binder
    }

    override fun onDestroy() {
        FakeBtSource.stop(this)
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
        const val ACTION_DIM_BIND = "com.geely.mockcar.DIM_BIND"
        const val ACTION_BT_PLAY = "com.geely.mockcar.BT_PLAY"
        const val ACTION_BT_PAUSE = "com.geely.mockcar.BT_PAUSE"
        const val ACTION_BT_STOP = "com.geely.mockcar.BT_STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ARTWORK_DELAY_MS = "artwork_delay_ms"
    }
}
