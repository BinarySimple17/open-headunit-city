package com.geely.mockcar

/**
 * Pure semantics of the mocked car property bus — no Android imports, JVM-testable
 * (MockCarStateTest). Every constant and rule mirrors the real Geely/ECARX firmware's
 * property vocabulary.
 *
 * The mock plays the role of a real car: writes that violate the documented value set are
 * rejected (false), so a broken client is visible in the logs instead of silently corrupting
 * state.
 */
object MockCarSpec {

    // Property IDs (Geely/ECARX VHAL)
    const val PROP_SEAT_HEATING = 268763648
    const val PROP_SEAT_VENTILATION = 268763392
    const val PROP_STEERING_WHEEL_HEAT = 269025536
    const val PROP_DEFROST_FRONT_MAX = 268698112
    const val PROP_DEFROST_REAR = 268698368
    const val PROP_CIRCULATION = 268632320
    const val PROP_AC = 268501760

    // Model-specific front windshield defrost property IDs
    val DEFROST_FRONT_IDS = intArrayOf(269755136, 269753088, 269027328) // cityray, preface, atlas

    // Zones (the firmware's VehicleSeat bitmask family)
    val SEAT_ZONES = intArrayOf(1, 4, 16, 64)
    const val GLOBAL_AREA = Int.MIN_VALUE

    // Encoded levels: base-of-property | level, 0 = off
    val SEAT_HEAT_LEVELS = intArrayOf(0, 268763649, 268763650, 268763651)
    val SEAT_VENT_LEVELS = intArrayOf(0, 268763393, 268763394, 268763395)
    val WHEEL_HEAT_LEVELS = intArrayOf(0, 269025537, 269025538, 269025539)

    // Auto mode (F008): dedicated AUTO properties with the same
    // base|level encoding; while a seat zone's auto is on, the main seat property answers the
    // auto read code — that is how the real firmware reports "auto" on reads.
    const val CIRCULATION_ON = 268632321
    const val CIRCULATION_OFF = 268632322
    const val PROP_AUTO_SEAT_HEATING = 268764416
    val AUTO_SEAT_HEAT_LEVELS = intArrayOf(0, 268764417, 268764418, 268764419)
    const val PROP_AUTO_STEERING_WHEEL_HEAT = 269025792
    val AUTO_WHEEL_HEAT_LEVELS = intArrayOf(0, 269025793, 269025794, 269025795)
    const val SEAT_HEAT_AUTO_ANSWER = 268763663 // 0x1005020F

    // Day/night signal (F003): the vendor property the firmware uses as the system-wide
    // day/night state (0x21401d9b; Cityray-AutoTheme reads it over root telnet — конспект §1).
    // 1 = day, 0 = night; global area 0.
    const val PROP_DAY_NIGHT = 557850011
    const val DAY_NIGHT_AREA = 0

    /** True when [propertyId] is one the mock knows how to validate. */
    fun isKnown(propertyId: Int, areaId: Int): Boolean = when (propertyId) {
        PROP_SEAT_HEATING, PROP_SEAT_VENTILATION -> SEAT_ZONES.contains(areaId)
        PROP_AUTO_SEAT_HEATING -> SEAT_ZONES.contains(areaId)
        PROP_AUTO_STEERING_WHEEL_HEAT -> true
        PROP_STEERING_WHEEL_HEAT,
        PROP_DEFROST_FRONT_MAX,
        PROP_DEFROST_REAR,
        PROP_CIRCULATION,
        PROP_AC -> true

        PROP_DAY_NIGHT -> areaId == DAY_NIGHT_AREA

        in DEFROST_FRONT_IDS.toSet() -> true
        else -> false
    }

    /**
     * Folds a client-supplied value into what a real car would store, or null when the write
     * must be rejected. Level properties accept both the encoded level and a plain ordinal
     * (the firmware answers in either form) — the encoded form is stored
     * so read-back matches the real car.
     */
    fun normalize(propertyId: Int, value: Int): Int? = when (propertyId) {
        PROP_SEAT_HEATING -> normalizeLevel(value, SEAT_HEAT_LEVELS)
        PROP_SEAT_VENTILATION -> normalizeLevel(value, SEAT_VENT_LEVELS)
        PROP_STEERING_WHEEL_HEAT -> normalizeLevel(value, WHEEL_HEAT_LEVELS)
        PROP_AUTO_SEAT_HEATING -> normalizeLevel(value, AUTO_SEAT_HEAT_LEVELS)
        PROP_AUTO_STEERING_WHEEL_HEAT -> normalizeLevel(value, AUTO_WHEEL_HEAT_LEVELS)
        PROP_DEFROST_FRONT_MAX, PROP_DEFROST_REAR, PROP_AC -> if (value == 0 || value == 1) value else null
        PROP_DAY_NIGHT -> if (value == 0 || value == 1) value else null
        PROP_CIRCULATION -> when (value) {
            CIRCULATION_ON, CIRCULATION_OFF -> value
            else -> null
        }

        in DEFROST_FRONT_IDS.toSet() -> if (value == 0 || value == 1) value else null
        else -> null
    }

    private fun normalizeLevel(value: Int, levels: IntArray): Int? = when {
        value == 0 -> 0
        levels.contains(value) -> value
        value in 1 until levels.size -> levels[value]
        else -> null
    }
}

/**
 * In-memory property store, keyed by propertyId/areaId. Unset values read as 0
 * ("everything off" — the state a parked car starts from).
 */
class MockCarState {

    private val values = mutableMapOf<Long, Int>()

    init {
        // A parked car starts in daylight; adb SET flips it to 0 (night) for the watch.
        set(MockCarSpec.PROP_DAY_NIGHT, MockCarSpec.DAY_NIGHT_AREA, 1)
    }

    /**
     * Reads a property. While a seat zone's auto mode is on, the main seat property answers
     * with the firmware's auto read code instead of the stored manual level.
     */
    fun get(propertyId: Int, areaId: Int): Int {
        if (propertyId == MockCarSpec.PROP_SEAT_HEATING &&
            get(MockCarSpec.PROP_AUTO_SEAT_HEATING, areaId) != 0
        ) {
            return MockCarSpec.SEAT_HEAT_AUTO_ANSWER
        }
        return values.getOrDefault(key(propertyId, areaId), 0)
    }

    /** Validates via [MockCarSpec]; returns false for unknown properties or illegal values. */
    fun set(propertyId: Int, areaId: Int, value: Int): Boolean {
        if (!MockCarSpec.isKnown(propertyId, areaId)) return false
        val normalized = MockCarSpec.normalize(propertyId, value) ?: return false
        values[key(propertyId, areaId)] = normalized
        return true
    }

    fun clear() = values.clear()

    fun snapshot(): Map<Long, Int> = values.toMap()

    private fun key(propertyId: Int, areaId: Int): Long =
        (propertyId.toLong() shl 32) or (areaId.toLong() and 0xFFFFFFFFL)
}

/**
 * The one store for the whole process: the service's binder serves it and the activity's
 * table shows it (service and UI share one process).
 */
object MockCarStore {
    val state = MockCarState()
}
