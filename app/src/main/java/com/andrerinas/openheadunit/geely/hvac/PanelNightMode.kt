package com.andrerinas.openheadunit.geely.hvac

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.geely.panel.PanelTheme
import com.andrerinas.openheadunit.utils.NightMode
import com.andrerinas.openheadunit.utils.Settings as AppSettings

/**
 * Day/night of the projection for the HVAC panel (F005), split out of the controller:
 * resolved once at attach from the app's night-mode setting (the CAR_SIGNAL choice reads
 * the same "car_mode" system setting NightModeManager's own sources do), then tracked via
 * the app's night-mode broadcast — the same signal that switches Android Auto itself to the
 * dark top bar; the poller repaints, so a missed flip self-corrects. Owns the receiver
 * lifecycle: [start] registers (and applies the initial guess to the theme), [stop]
 * unregisters.
 */
class PanelNightMode(
    private val context: Context,
    private val theme: PanelTheme,
    private val onFlip: () -> Unit,
) {

    private var receiver: BroadcastReceiver? = null

    fun start() {
        theme.isNight = resolveInitial()
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (theme.isNight != intent.getBooleanExtra("isNight", false)) {
                    theme.isNight = intent.getBooleanExtra("isNight", false)
                    onFlip()
                }
            }
        }
        ContextCompat.registerReceiver(
            context, r, IntentFilter(AapService.ACTION_NIGHT_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiver = r
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    /** Initial day/night guess from the NightModeManager's own sources; the live broadcast
     *  corrects what it cannot see (sensor/brightness/location modes). */
    private fun resolveInitial(): Boolean {
        val appSettings = App.provide(context).settings
        return when (appSettings.nightMode) {
            AppSettings.NightMode.NIGHT -> true
            AppSettings.NightMode.DAY -> false
            AppSettings.NightMode.CAR_SIGNAL -> try {
                android.provider.Settings.System.getInt(context.contentResolver, "car_mode") != 0
            } catch (_: Exception) { false }
            else -> NightMode(appSettings, false).current
        }
    }
}
