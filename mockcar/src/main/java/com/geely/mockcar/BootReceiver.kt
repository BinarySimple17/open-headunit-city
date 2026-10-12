package com.geely.mockcar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the mock after boot so it "just exists" without anyone opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, MockCarService::class.java))
        }
    }
}
