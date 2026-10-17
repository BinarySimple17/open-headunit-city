package com.andrerinas.openheadunit.geely.daynight

/**
 * The car's day/night signal (F003): vendor VHAL property 0x21401d9b (557850011;
 * VENDOR|GLOBAL|INT32_VEC, index 0x1d9b), area 0, 1 = day, 0 = night. The car computes dark
 * time itself (headlights/ambient sensor); nothing is derived here. Decoded from the
 * Cityray-AutoTheme reference — docs/geely-references.md §1.
 */
object DayNight {
    const val PROPERTY = 557850011
    const val AREA = 0
    const val DAY = 1
    const val NIGHT = 0

    /** Reference rhythm: poll every 2 s, apply a fresh value after 2 equal consecutive samples. */
    const val POLL_INTERVAL_MS = 2000L
    const val CONFIRM_SAMPLES = 2
}

/**
 * Two-sample debounce of the day/night reads (the reference's VhalDebouncer): a fresh value
 * applies only after [DayNight.CONFIRM_SAMPLES] equal consecutive reads, while the first
 * valid reading — and any moment [requestImmediate] was called — applies immediately.
 * An unreadable or out-of-vocabulary sample drops the candidate and keeps the applied value,
 * so a flapping channel cannot flap the theme.
 */
class DayNightDebouncer {

    enum class Decision { NONE, APPLY_IMMEDIATE, APPLY_CONFIRMED }

    private var applied: Int? = null
    private var candidate: Int? = null
    private var candidateCount = 0
    private var immediate = true

    /** The next valid sample applies without confirmation (explicit re-sync). */
    @Synchronized fun requestImmediate() {
        immediate = true
    }

    @Synchronized fun onValid(value: Int): Decision {
        if (value != DayNight.DAY && value != DayNight.NIGHT) return onInvalid()
        if (immediate) return Decision.APPLY_IMMEDIATE
        val last = applied
        if (last != null && last == value) {
            candidate = null
            candidateCount = 0
            return Decision.NONE
        }
        if (candidate != null && candidate == value) {
            candidateCount++
        } else {
            candidate = value
            candidateCount = 1
        }
        return if (candidateCount >= DayNight.CONFIRM_SAMPLES) Decision.APPLY_CONFIRMED else Decision.NONE
    }

    /** An unreadable sample: the candidate is dropped, the applied value stays. */
    @Synchronized fun onInvalid(): Decision {
        candidate = null
        candidateCount = 0
        return Decision.NONE
    }

    @Synchronized fun markApplied(value: Int) {
        applied = value
        candidate = null
        candidateCount = 0
        immediate = false
    }

    /** Fresh state: the next valid sample applies immediately (new session). */
    @Synchronized fun reset() {
        applied = null
        candidate = null
        candidateCount = 0
        immediate = true
    }
}
