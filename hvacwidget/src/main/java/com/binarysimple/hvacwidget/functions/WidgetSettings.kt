package com.binarysimple.hvacwidget.functions

import android.content.Context
import android.content.SharedPreferences

/**
 * Widget preferences in their own file — a standalone copy of the :app panel's HvacSettings
 * pattern (D-008: the widget does NOT read :app settings). The model gates the front-defrost
 * property id and the seat-memory slot ladder ([WidgetFunctions.Model]); the default is the
 * planner's car. There is no config Activity — the widget has none (the picker needs none),
 * the value changes via adb until a settings surface is ever requested.
 */
class WidgetSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    var carModel: WidgetFunctions.Model
        get() = WidgetFunctions.Model.entries.getOrElse(
            prefs.getInt(KEY_CAR_MODEL, WidgetFunctions.Model.CITYRAY.ordinal),
        ) { WidgetFunctions.Model.CITYRAY }
        set(value) = prefs.edit().putInt(KEY_CAR_MODEL, value.ordinal).apply()

    /**
     * The last restored memory-slot value — the SeatMemoryWidget reference's "selected slot"
     * (geely-references 2e): when the car gives no answer for the memory property, the last
     * slot the user tapped stays highlighted instead of all reading "unknown".
     */
    var lastMemoryRestore: Int
        get() = prefs.getInt(KEY_LAST_MEMORY_RESTORE, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_MEMORY_RESTORE, value).apply()

    private companion object {
        const val PREFS_FILE = "hvacwidget_prefs"
        const val KEY_CAR_MODEL = "car_model"
        const val KEY_LAST_MEMORY_RESTORE = "last_memory_restore"
    }
}
