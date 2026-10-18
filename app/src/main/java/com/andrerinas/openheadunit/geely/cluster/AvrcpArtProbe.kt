package com.andrerinas.openheadunit.geely.cluster

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.MediaMetadata
import com.andrerinas.openheadunit.geely.hvac.HvacLog

/**
 * The in-process cover-art probe (F013, round 10 quest): adb on the unit is out of the
 * planner's reach, so the app asks the AVRCP controller stack DIRECTLY. The published
 * media session can strip artwork a stack actually holds — the controller profile
 * ([BluetoothProfile] id 12, `BluetoothAvrcpController`, reflectively: the class is not
 * public API) answers `getMetadata(device)` with what AVRCP/BIP really delivered, cover
 * art included. Pure diagnostics: everything lands in the car log, nothing is published.
 *
 * Failure IS data: "proxy denied" means the firmware walls the stack off from third
 * parties, "no metadata" means no cover was ever fetched — both verdicts close or steer
 * the quest. Every call is guarded; the probe can never break the card.
 */
object AvrcpArtProbe {

    /** [BluetoothProfile.AVRCP_CONTROLLER] — a public constant, the class behind it is not. */
    private const val AVRCP_CONTROLLER_PROFILE = 12

    @Volatile private var proxy: BluetoothProfile? = null
    @Volatile private var bindAttempted = false

    /** Bind once per process; later calls reuse the proxy. Never throws. */
    fun ensureBound(context: Context) {
        if (bindAttempted) return
        bindAttempted = true
        runCatching {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter ?: return
            val app = context.applicationContext
            adapter.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, binder: BluetoothProfile) {
                    if (profile == AVRCP_CONTROLLER_PROFILE) {
                        proxy = binder
                        HvacLog.i("cluster.bt: avrcp probe bound (${binder.javaClass.name})")
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == AVRCP_CONTROLLER_PROFILE) proxy = null
                }
            }, AVRCP_CONTROLLER_PROFILE)
        }.onFailure {
            HvacLog.w("cluster.bt: avrcp probe bind failed: ${it.message}")
        }
    }

    /** One car-log line per track change: does the stack itself hold cover art? */
    fun dump() {
        val bound = proxy
        if (bound == null) {
            HvacLog.d("cluster.bt: avrcp probe not bound (proxy denied or no stack)")
            return
        }
        runCatching {
            val devices = bound.connectedDevices
            val device = devices.firstOrNull()
            if (device == null) {
                HvacLog.d("cluster.bt: avrcp probe: no connected device")
                return
            }
            val meta = bound.javaClass.methods
                .firstOrNull { it.name == "getMetadata" }
                ?.invoke(bound, device) as? MediaMetadata
            val keys = meta?.let { BtMediaMonitor.describeKeys(it) } ?: "(null)"
            HvacLog.d("cluster.bt: avrcp probe (${"device ${devices.size}"}): $keys")
        }.onFailure {
            HvacLog.d("cluster.bt: avrcp probe failed: ${it.message}")
        }
    }
}
