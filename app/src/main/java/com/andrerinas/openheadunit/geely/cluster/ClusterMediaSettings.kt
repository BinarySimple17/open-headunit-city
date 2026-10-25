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

    /**
     * Candidate Ж (BL-004 facts 20/25): publish the card with sourceType=2 (SOURCE_TYPE_BT)
     * instead of 6 (SOURCE_TYPE_ONLINE). If the cluster keeps cards per source type, our
     * push then feeds the very slot the stock Bluetooth card occupies — no second card, no
     * arbitration. Risk: the BT slot's renderer may not draw artwork at all (round 6 proved
     * the type affects rendering).
     */
    var sourceTypeBt: Boolean
        get() = prefs.getBoolean(KEY_SOURCE_TYPE_BT, false)
        set(value) = prefs.edit().putBoolean(KEY_SOURCE_TYPE_BT, value).apply()

    /**
     * Candidate З (BL-004 facts 20/25): announce the active audio source to the cluster
     * (updateCurrentSourceType, a firmware setter of our own DIM channel we never called)
     * on every card publication. Hypothesis: the cluster flicks cards because nobody
     * declares the current source; declaring BT while the A2DP audio plays removes the
     * reason to switch "to Bluetooth". Independent of [sourceTypeBt].
     */
    var sourceAnnounce: Boolean
        get() = prefs.getBoolean(KEY_SOURCE_ANNOUNCE, false)
        set(value) = prefs.edit().putBoolean(KEY_SOURCE_ANNOUNCE, value).apply()

    /**
     * The observation surface (BL-004 fact 25): poll the vendor media center over its
     * oneosapi binder for what the STOCK Bluetooth feed currently holds (title, artist,
     * cover, play state) and log every change to the HVAC log. Read-only diagnostics for
     * the flicker hunt — it prints what the competing publisher knows and when.
     */
    var mediaCenterProbe: Boolean
        get() = prefs.getBoolean(KEY_MEDIA_CENTER_PROBE, false)
        set(value) = prefs.edit().putBoolean(KEY_MEDIA_CENTER_PROBE, value).apply()

    /**
     * Candidate И (BL-004 fact 41): subscribe to the cluster's own message broker
     * (`ihu/#` on the loopback MQTT the firmware's dimsupportservice feeds from) and
     * echo every frame to the HVAC log. The stock publisher and the projection-axis
     * switches become observable without root; read-only, nothing is ever published.
     */
    var brokerObserver: Boolean
        get() = prefs.getBoolean(KEY_BROKER_OBSERVER, false)
        set(value) = prefs.edit().putBoolean(KEY_BROKER_OBSERVER, value).apply()

    /**
     * Candidate К (BL-004 fact 41): while our card stands, publish the projection-axis
     * frame (`androidAutoMedia`) the firmware's own CarPlay path uses — the cluster then
     * reads its media card from the projection instead of flipping back to the head
     * unit's own Bluetooth source. Changes a system-wide head-unit state; turning it
     * off undoes our pin and hands the card back to the stock sources.
     */
    var brokerPinProjection: Boolean
        get() = prefs.getBoolean(KEY_BROKER_PIN, false)
        set(value) = prefs.edit().putBoolean(KEY_BROKER_PIN, value).apply()

    /** Watches this file — the bridge reacts to Save without anyone wiring it by hand. */
    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private companion object {
        const val PREFS_FILE = "cluster_media_prefs"
        const val KEY_ENABLED = "cluster_media_enabled"
        const val KEY_BT_FALLBACK = "cluster_bt_fallback"
        const val KEY_PROGRESS = "cluster_progress_enabled"
        const val KEY_SOURCE_TYPE_BT = "cluster_source_type_bt"
        const val KEY_SOURCE_ANNOUNCE = "cluster_source_announce"
        const val KEY_MEDIA_CENTER_PROBE = "cluster_mediacenter_probe"
        const val KEY_BROKER_OBSERVER = "cluster_broker_observer"
        const val KEY_BROKER_PIN = "cluster_broker_pin_projection"
    }
}
