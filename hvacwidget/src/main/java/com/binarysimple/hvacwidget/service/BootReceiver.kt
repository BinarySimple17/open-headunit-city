package com.binarysimple.hvacwidget.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.binarysimple.hvacwidget.ClimateWidgetProvider

/**
 * Restores the widget after a head-unit reboot or an app update (docs/hvac-widget-app.md 3):
 * restarts the status poll and repaints the buttons from the (empty until the first poll)
 * snapshot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        StatusPollService.start(context)
        ClimateWidgetProvider.pushAll(context)
    }
}
