package com.andrerinas.openheadunit

import android.app.NotificationManager
import android.content.Context
import android.net.wifi.WifiManager
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.decoder.audio.AudioDecoder
import com.andrerinas.openheadunit.decoder.video.DeviceMemoryProfile
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.connection.carkey.CarKeysManager
import com.andrerinas.openheadunit.geely.cluster.ClusterMediaBridge
import com.andrerinas.openheadunit.geely.daynight.GeelyDayNightWatch
import com.andrerinas.openheadunit.utils.SUExecutor
import com.andrerinas.openheadunit.utils.Settings

class AppComponent(private val app: App) {

    val settings = Settings(app)
    // A function, not a reading: this decoder is a process singleton, so anything resolved here
    // once would outlive every settings change the user makes.
    val videoDecoder = VideoDecoder(settings) {
        DeviceMemoryProfile.readWithOverride(app, settings.debugForceMemoryProfile)
    }
    val audioDecoder = AudioDecoder()

    val notificationManager: NotificationManager
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val wifiManager: WifiManager
        get() = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val commManager = CommManager(app, settings, audioDecoder, videoDecoder)

    val suExecutor = SUExecutor()

    val carKeysManager = CarKeysManager()

    // Geely instrument-cluster media redirect (F013, isolated geely.cluster package): app-scoped
    // — it watches both switches itself and is fed from the AA media seam in AapService.
    val clusterMediaBridge: ClusterMediaBridge by lazy { ClusterMediaBridge(app) }

    // Geely day/night watch (F003, isolated geely.daynight package): app-scoped poller of the
    // car's day/night VHAL property; its answer feeds NightModeManager's CAR_SIGNAL mode
    // (the projection night theme) from the seams in AapService.
    val dayNightWatch: GeelyDayNightWatch by lazy { GeelyDayNightWatch(app) }
}
