package com.andrerinas.openheadunit.geely.cluster

import android.content.Context
import android.content.SharedPreferences

/**
 * The cluster-card toggle (F013) in its own prefs file, deliberately separate from the big
 * upstream [com.andrerinas.openheadunit.utils.Settings] monolith — the same isolation the
 * climate panel keeps (D-003). The card's only data source is the head unit's own Bluetooth
 * media session (planner decision 2026-10-18); these switches gate whether it publishes.
 */
class ClusterMediaSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Whether the Bluetooth track card is published to the instrument cluster. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * Whether the Bluetooth channel is armed: while the phone plays through the car's
     * A2DP/AVRCP channel, the card is fed from the head unit's own Bluetooth media session
     * (the stock card's own data, plus artwork — the reference's trick, конспект §2c).
     * Needs the notification-access grant to read sessions at all.
     */
    var btFallback: Boolean
        get() = prefs.getBoolean(KEY_BT_FALLBACK, false)
        set(value) = prefs.edit().putBoolean(KEY_BT_FALLBACK, value).apply()

    /**
     * The D2 experiment switch (S015 round 2026-10-22, BL-004 fact 20): whether the 1 Hz
     * position ticks go to the cluster at all. The card itself (uuid, cover, title) is
     * unaffected — the planner flips this MID-DRIVE the moment the flicker starts; if the
     * slot calms, the progress stream feeds the source arbitration and a low rate becomes
     * the fix; if nothing changes, the flicker is firmware-internal (candidate B verdict).
     */
    var progressEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROGRESS, true)
        set(value) = prefs.edit().putBoolean(KEY_PROGRESS, value).apply()

    /** Watches this file — the bridge reacts to Save without anyone wiring it by hand. */
    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private companion object {
        const val PREFS_FILE = "cluster_media_prefs"
        const val KEY_ENABLED = "cluster_media_enabled"
        const val KEY_BT_FALLBACK = "cluster_bt_fallback"
        const val KEY_PROGRESS = "cluster_progress_enabled"
    }
}
