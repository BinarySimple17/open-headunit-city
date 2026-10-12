package com.andrerinas.openheadunit.geely.hvac

/**
 * Pure HVAC domain logic for the Geely/ECARX climate panel — no Android imports, so the whole
 * file is unit-testable on the JVM (HvacLogicTest).
 *
 * Every constant below is the Geely/ECARX firmware's property vocabulary (VHAL ids, seat zone
 * bitmasks, base|level encodings). Levels are NOT 1/2/3 — the platform encodes a level as
 * base-of-property | level, and reads may answer with "auto" codes that have to fold back into
 * a displayable level.
 */
object Hvac {

    /** The platform's "global zone" sentinel (the adaptapi convention: Integer.MIN_VALUE). */
    const val GLOBAL_AREA: Int = Int.MIN_VALUE

    // --- Property IDs (Geely/ECARX VHAL) ---
    const val PROP_SEAT_HEATING = 268763648
    const val PROP_STEERING_WHEEL_HEAT = 269025536
    const val PROP_DEFROST_REAR = 268698368
    const val PROP_DEFROST_FRONT_MAX = 268698112
    const val PROP_CIRCULATION = 268632320

    // --- Seat zones (the firmware's VehicleSeat bitmask family) ---
    const val ZONE_DRIVER = 1
    const val ZONE_PASSENGER = 4
    const val ZONE_REAR_LEFT = 16
    const val ZONE_REAR_RIGHT = 64

    // --- Levels: base-of-property | level, 0 = off ---
    val SEAT_HEAT_LEVELS = intArrayOf(0, 268763649, 268763650, 268763651)
    val WHEEL_HEAT_LEVELS = intArrayOf(0, 269025537, 269025538, 269025539)

    /** "auto" codes observed instead of a level when reading seat heat / ventilation. */
    const val READ_AUTO_SEAT_HEAT = 0x1005020F
    const val READ_AUTO_SEAT_VENT = 0x1005010F

    const val CIRCULATION_ON = 268632321
    const val CIRCULATION_OFF = 268632322

    // --- Auto mode (F008): dedicated AUTO properties, the same
    // base|level encoding as the manual ones. Enabling auto makes the main seat property
    // answer the known auto read code (READ_AUTO_SEAT_HEAT). ---
    const val PROP_AUTO_SEAT_HEATING = 268764416
    val AUTO_SEAT_HEAT_LEVELS = intArrayOf(0, 268764417, 268764418, 268764419)
    const val PROP_AUTO_STEERING_WHEEL_HEAT = 269025792
    val AUTO_WHEEL_HEAT_LEVELS = intArrayOf(0, 269025793, 269025794, 269025795)

    /** Head-unit models with a model-specific front-defrost property ID. */
    enum class CarModel(val defrostFrontPropertyId: Int) {
        CITYRAY(269755136),
        PREFACE(269753088),
        ATLAS(269027328),
    }

    enum class PanelMode { COLLAPSED, ALWAYS }

    /** Choices for the icon auto-hide timeout setting (seconds). */
    val HIDE_TIMEOUT_CHOICES = listOf(5, 10, 15, 30)

    /**
     * Folds a raw read of a level property into one of [levels] (0 = off).
     * The firmware answers either with a plain ordinal
     * (1..3) or with the encoded level itself; seat heat additionally reports [READ_AUTO_SEAT_HEAT]
     * for "auto" (ventilation uses [READ_AUTO_SEAT_VENT]) which folds into the top level.
     * Anything unrecognised counts as off — never guess a level from garbage.
     */
    fun normalizeLevel(raw: Int, levels: IntArray, autoReadCode: Int? = null): Int = when {
        raw <= 0 -> 0
        raw == autoReadCode -> levels.last()
        levels.contains(raw) -> raw
        raw in 1 until levels.size -> levels[raw]
        else -> 0
    }

    /**
     * Index of the level to apply after a tap on a level button: unknown/off starts at L1
     * (a missing index counts as 0 then steps forward), the top level wraps to off.
     */
    fun nextLevelIndex(currentNormalized: Int, levels: IntArray): Int =
        nextLevelOrdinal(levels.indexOf(currentNormalized).takeIf { it >= 0 } ?: 0, levels)

    /** Same cycle as [nextLevelIndex] but over the display ordinal (0 = off, 1..3 = L1..L3). */
    fun nextLevelOrdinal(currentOrdinal: Int, levels: IntArray): Int {
        val index = if (currentOrdinal in 1..levels.lastIndex) currentOrdinal else 0
        return if (index >= levels.lastIndex) 0 else index + 1
    }

    /** True when the read value means the defrost (0/1 property) is on. */
    fun isDefrostOn(raw: Int): Boolean = raw == 1

    /** True when the read value means recirculation is on (property answers ON/OFF codes, not 0/1). */
    fun isRecirculationOn(raw: Int): Boolean = raw == CIRCULATION_ON

    // --- Status scheme: the panel buttons mirror real property reads ---

    const val KEY_WHEEL = "wheel"
    const val KEY_WHEEL_AUTO = "wheel_auto"
    const val KEY_DEFROST_FRONT = "defrost_front"
    const val KEY_DEFROST_REAR = "defrost_rear"
    const val KEY_DEFROST_MAX = "defrost_max"
    const val KEY_RECIRC = "recirc"

    /** Seat status keys in panel order: driver, passenger, rear-left, rear-right. */
    val SEAT_KEYS = listOf(seatKey(ZONE_DRIVER), seatKey(ZONE_PASSENGER), seatKey(ZONE_REAR_LEFT), seatKey(ZONE_REAR_RIGHT))

    /** "seat:<area>" status key of a seat zone. */
    fun seatKey(area: Int): String = "seat:$area"

    /** "seat_auto:<area>" status key of a seat zone's auto mode. */
    fun seatAutoKey(area: Int): String = "seat_auto:$area"

    /** Zone of a "seat:<area>" / "seat_auto:<area>" status key. */
    fun seatZoneOfKey(key: String): Int = key.substringAfter(':').toInt()

    /** Every tracked status key in panel order: four seat zones + their auto, wheel + its auto, three defrosts, recirculation. */
    fun statusKeys(): List<String> = GeelyCarFunctions.all(CarModel.CITYRAY).map { it.id }

    /**
     * Reads every tracked property through the port's quiet path (polling must not flood the
     * HVAC log) and returns raw values keyed by status key; `null` = no answer. The read set
     * comes from [GeelyCarFunctions] — the single source of tracked properties (F010).
     */
    fun readStatus(port: CarFunctionPort, model: CarModel): Map<String, Int?> =
        LinkedHashMap<String, Int?>().apply {
            for (spec in GeelyCarFunctions.all(model)) {
                put(spec.id, port.getIntQuiet(spec.propertyId, spec.areaId))
            }
        }

    /** Keys whose raw value differs between two snapshots (an absent previous value counts as changed). */
    fun changedKeys(prev: Map<String, Int?>, next: Map<String, Int?>): List<String> =
        next.filter { (key, value) -> prev[key] != value }.keys.toList()

    /**
     * Display ordinal (0..3) shown through the icon color/badge of a level button; 0 = off,
     * unknown or a property without levels. Facade over the registry specs (F010) — the panel
     * itself works on [CarFunctionSpec] directly; this keeps the status-key API of the
     * HvacLogicTest suite and the log lines stable.
     */
    fun buttonLevel(key: String, raw: Int?): Int =
        GeelyCarFunctions.byId(key)?.levelOf(raw) ?: 0

    /** What a button shows: while auto runs, its level; otherwise the manual one. */
    fun displayLevel(manualLevel: Int, autoLevel: Int): Int = if (autoLevel > 0) autoLevel else manualLevel

    /** Whether the button for a status key reads as on (colored icon) for the raw property value. */
    fun buttonActive(key: String, raw: Int?): Boolean =
        GeelyCarFunctions.byId(key)?.isActive(raw) ?: false
}
