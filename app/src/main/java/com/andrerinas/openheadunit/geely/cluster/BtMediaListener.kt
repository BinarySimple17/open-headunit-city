package com.andrerinas.openheadunit.geely.cluster

import android.service.notification.NotificationListenerService
import com.andrerinas.openheadunit.App

/**
 * The grant-holder of the Bluetooth side of the cluster card (F013): reading the head unit's
 * local media sessions (the car's own Bluetooth app holding the phone's AVRCP metadata)
 * requires this app to be the connected notification listener — exactly how the reference
 * does it (конспект §2c). The service itself does nothing; its presence unlocks
 * [BtMediaMonitor], which the user arms with the "Bluetooth music" switch in the GEELY tab
 * plus the system notification-access grant.
 *
 * Manifest declaration mirrors the reference: exported service bound by the system under
 * BIND_NOTIFICATION_LISTENER_SERVICE with the listener action intent-filter.
 */
class BtMediaListener : NotificationListenerService() {

    @Volatile private var alive = false

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onListenerConnected() {
        alive = true
        App.provide(this).clusterMediaBridge.btAccessChanged()
    }

    override fun onListenerDisconnected() {
        alive = false
        App.provide(this).clusterMediaBridge.btAccessChanged()
    }

    override fun onDestroy() {
        alive = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: BtMediaListener? = null

        /** True while this app is the bound, connected notification listener. */
        val connected: Boolean
            get() = instance?.alive == true
    }
}
