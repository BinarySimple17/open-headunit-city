package com.binarysimple.hvacwidget.car

/**
 * The seam between the widget and the car — a deliberate standalone copy of the :app panel's
 * port interface (D-008: zero shared classes with :app; the duplication is conscious and
 * hand-synced). The widget code and the JVM tests talk to this; only [EcarxWidgetPort] sits
 * behind it on the real head unit.
 */
interface CarFunctionPort {

    /** True when a real car-service connection is established and calls are worth making. */
    val available: Boolean

    /**
     * Reads an int property for a zone. `null` means "no answer" (service unavailable, call
     * threw) — callers treat it as unknown, never as a value.
     */
    fun getInt(propertyId: Int, areaId: Int): Int?

    /**
     * Quiet read for the status poller (every function every few seconds): no per-call
     * success logging, or the log would be nothing but poll noise. Failures are still
     * logged once per property; the poller's diff reports changed values.
     */
    fun getIntQuiet(propertyId: Int, areaId: Int): Int? = getInt(propertyId, areaId)

    /** Writes an int property for a zone; false means the write did not go through. */
    fun setInt(propertyId: Int, areaId: Int, value: Int): Boolean

    /**
     * Connect-time support verdict for a property/area (the port's own probe, when it makes
     * one). Optimistic by default — ports without probe data answer yes and let the write
     * itself produce the verdict.
     */
    fun isFunctionSupported(propertyId: Int, areaId: Int): Boolean = true
}
