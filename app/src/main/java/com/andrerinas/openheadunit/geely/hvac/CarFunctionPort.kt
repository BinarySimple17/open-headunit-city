package com.andrerinas.openheadunit.geely.hvac

/**
 * The seam between the climate panel and the car: the panel and the JVM tests talk to this,
 * only [AndroidCarPort] (the standard android.car backend, D-006) and the debug mock talk
 * to a real car service behind it.
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
     * Quiet read for the status poller (the panel re-reads every property every couple of
     * seconds): no per-call success logging, or the HVAC log would be nothing but poll noise.
     * Failures are still logged; changed values are logged once by the panel's diff.
     */
    fun getIntQuiet(propertyId: Int, areaId: Int): Int? = getInt(propertyId, areaId)

    /** Writes an int property for a zone; false means the write did not go through. */
    fun setInt(propertyId: Int, areaId: Int, value: Int): Boolean

    /**
     * Connect-time support verdict for a property/area (the port's own probe, when it makes
     * one). Optimistic by default — ports without probe data answer yes and let the write
     * itself produce the verdict; EcarxCarPort answers from its probe lines, so a property
     * the car reported `notavailable` is not written blindly.
     */
    fun isFunctionSupported(propertyId: Int, areaId: Int): Boolean = true
}
