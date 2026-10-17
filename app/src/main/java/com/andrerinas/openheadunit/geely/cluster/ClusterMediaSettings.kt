package com.andrerinas.openheadunit.geely.cluster

import android.content.Context
import android.content.SharedPreferences

/**
 * The cluster-card toggle (F013) in its own prefs file, deliberately separate from the big
 * upstream [com.andrerinas.openheadunit.utils.Settings] monolith — the same isolation the
 * climate panel keeps (D-003). It exists only ON TOP of the native "AA now playing on media
 * session" switch: that one stays the master switch and the data source, this one only
 * redirects the same stream into the instrument cluster.
 */
class ClusterMediaSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Whether AA metadata is additionally published to the instrument cluster. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * Whether the Bluetooth fallback is armed: while the phone plays through the car's
     * A2DP/AVRCP channel, the card is fed from the head unit's own Bluetooth media session
     * (the stock card's own data, plus artwork — the reference's trick, конспект §2c).
     * Needs the notification-access grant to read sessions at all.
     */
    var btFallback: Boolean
        get() = prefs.getBoolean(KEY_BT_FALLBACK, false)
        set(value) = prefs.edit().putBoolean(KEY_BT_FALLBACK, value).apply()

    /** Watches this file — the bridge reacts to Save without anyone wiring it by hand. */
    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private companion object {
        const val PREFS_FILE = "cluster_media_prefs"
        const val KEY_ENABLED = "cluster_media_enabled"
        const val KEY_BT_FALLBACK = "cluster_bt_fallback"
    }
}
