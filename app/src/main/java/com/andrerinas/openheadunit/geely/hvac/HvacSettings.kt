package com.andrerinas.openheadunit.geely.hvac

import android.content.Context
import android.content.SharedPreferences

/**
 * Climate-panel preferences in their own file (`hvac_prefs`), deliberately separate from the
 * big [com.andrerinas.openheadunit.utils.Settings] monolith — the panel is Geely-specific and
 * D-003 keeps its footprint off shared upstream state.
 */
class HvacSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    var panelMode: Hvac.PanelMode
        get() = Hvac.PanelMode.entries.getOrElse(prefs.getInt(KEY_PANEL_MODE, Hvac.PanelMode.COLLAPSED.ordinal)) { Hvac.PanelMode.COLLAPSED }
        set(value) = prefs.edit().putInt(KEY_PANEL_MODE, value.ordinal).apply()

    var carModel: Hvac.CarModel
        get() = Hvac.CarModel.entries.getOrElse(prefs.getInt(KEY_CAR_MODEL, Hvac.CarModel.CITYRAY.ordinal)) { Hvac.CarModel.CITYRAY }
        set(value) = prefs.edit().putInt(KEY_CAR_MODEL, value.ordinal).apply()

    /** When true, HvacLog mirrors every panel/bridge event into HVAC_Log_*.txt. */
    var debugLog: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_LOG, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_LOG, value).apply()

    /** Seconds before the expanded icons auto-collapse (COLLAPSED mode only). */
    var hideTimeoutSec: Int
        get() = prefs.getInt(KEY_HIDE_TIMEOUT_SEC, DEFAULT_HIDE_TIMEOUT_SEC)
        set(value) = prefs.edit().putInt(KEY_HIDE_TIMEOUT_SEC, value).apply()

    /** Seat auto-heat level (1..3) applied by a long-press when the seat is currently off. */
    var seatAutoLevel: Int
        get() = prefs.getInt(KEY_SEAT_AUTO_LEVEL, DEFAULT_SEAT_AUTO_LEVEL)
        set(value) = prefs.edit().putInt(KEY_SEAT_AUTO_LEVEL, value).apply()

    /** Wheel auto-heat level (1..3) applied by a long-press when the wheel is currently off. */
    var wheelAutoLevel: Int
        get() = prefs.getInt(KEY_WHEEL_AUTO_LEVEL, DEFAULT_WHEEL_AUTO_LEVEL)
        set(value) = prefs.edit().putInt(KEY_WHEEL_AUTO_LEVEL, value).apply()

    private companion object {
        const val PREFS_FILE = "hvac_prefs"
        const val KEY_PANEL_MODE = "panel_mode"
        const val KEY_CAR_MODEL = "car_model"
        const val KEY_DEBUG_LOG = "debug_log"
        const val KEY_HIDE_TIMEOUT_SEC = "hide_timeout_sec"
        const val KEY_SEAT_AUTO_LEVEL = "seat_auto_level"
        const val KEY_WHEEL_AUTO_LEVEL = "wheel_auto_level"
        const val DEFAULT_HIDE_TIMEOUT_SEC = 5
        const val DEFAULT_SEAT_AUTO_LEVEL = 2
        const val DEFAULT_WHEEL_AUTO_LEVEL = 1
    }
}
