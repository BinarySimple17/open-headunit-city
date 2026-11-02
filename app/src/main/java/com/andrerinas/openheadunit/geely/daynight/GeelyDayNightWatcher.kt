package com.andrerinas.openheadunit.geely.daynight

import com.andrerinas.openheadunit.geely.hvac.CarFunctionPort
import com.andrerinas.openheadunit.geely.hvac.HvacLog

/**
 * One poll of the car's day/night property through a [CarFunctionPort]; scheduling (every
 * [DayNight.POLL_INTERVAL_MS]) and thread dispatch are the owner's job. Read-only — nothing
 * is ever written to the car. The quiet read keeps the HVAC log free of per-poll noise;
 * channel events (ready, read failures, flips) are logged once per transition as
 * `daynight:` lines so a car round can be diagnosed after the fact.
 */
class GeelyDayNightWatcher(
    private val port: CarFunctionPort,
    private val onFlip: (isNight: Boolean) -> Unit,
) {
    private val debouncer = DayNightDebouncer()
    private var channelLogged = false
    private var readOk = true

    fun pollOnce() {
        val raw = port.getIntQuiet(DayNight.PROPERTY, DayNight.AREA)
        if (raw == null) {
            if (readOk) {
                readOk = false
                HvacLog.w("daynight: read failed; sample discarded")
            }
            debouncer.onInvalid()
            return
        }
        if (!readOk) {
            readOk = true
            HvacLog.i("daynight: read recovered")
        }
        if (!channelLogged) {
            channelLogged = true
            HvacLog.i("daynight: channel ready (prop=${DayNight.PROPERTY})")
        }
        when (debouncer.onValid(raw)) {
            DayNightDebouncer.Decision.NONE -> Unit
            DayNightDebouncer.Decision.APPLY_IMMEDIATE -> apply(raw, "immediate")
            DayNightDebouncer.Decision.APPLY_CONFIRMED -> apply(raw, "confirmed")
        }
    }

    private fun apply(value: Int, how: String) {
        debouncer.markApplied(value)
        val isNight = value == DayNight.NIGHT
        HvacLog.i("daynight: isNight=$isNight ($how)")
        onFlip(isNight)
    }
}
