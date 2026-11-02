package com.andrerinas.openheadunit.geely.daynight

import com.andrerinas.openheadunit.geely.daynight.DayNightDebouncer.Decision.APPLY_CONFIRMED
import com.andrerinas.openheadunit.geely.daynight.DayNightDebouncer.Decision.APPLY_IMMEDIATE
import com.andrerinas.openheadunit.geely.daynight.DayNightDebouncer.Decision.NONE
import org.junit.Assert.assertEquals
import org.junit.Test

class DayNightDebouncerTest {

    @Test
    fun `first valid value applies immediately`() {
        val d = DayNightDebouncer()
        assertEquals(APPLY_IMMEDIATE, d.onValid(DayNight.NIGHT))
        assertEquals(APPLY_IMMEDIATE, d.onValid(DayNight.DAY))
    }

    @Test
    fun `a transition needs two consecutive new samples`() {
        val d = DayNightDebouncer()
        d.markApplied(DayNight.DAY)
        assertEquals(NONE, d.onValid(DayNight.NIGHT))
        assertEquals(APPLY_CONFIRMED, d.onValid(DayNight.NIGHT))
    }

    @Test
    fun `same value as applied is none`() {
        val d = DayNightDebouncer()
        d.markApplied(DayNight.NIGHT)
        assertEquals(NONE, d.onValid(DayNight.NIGHT))
    }

    @Test
    fun `an interleaved sample resets the candidate count`() {
        val d = DayNightDebouncer()
        d.markApplied(DayNight.DAY)
        assertEquals(NONE, d.onValid(DayNight.NIGHT))
        assertEquals(NONE, d.onValid(DayNight.DAY))
        // The night candidate restarted from zero.
        assertEquals(NONE, d.onValid(DayNight.NIGHT))
        assertEquals(APPLY_CONFIRMED, d.onValid(DayNight.NIGHT))
    }

    @Test
    fun `garbage drops the candidate and keeps the applied value`() {
        val d = DayNightDebouncer()
        d.markApplied(DayNight.DAY)
        assertEquals(NONE, d.onValid(DayNight.NIGHT))
        assertEquals(NONE, d.onValid(42))
        assertEquals(NONE, d.onValid(-1))
        assertEquals(NONE, d.onValid(DayNight.NIGHT))
        assertEquals(APPLY_CONFIRMED, d.onValid(DayNight.NIGHT))
    }

    @Test
    fun `request immediate forces the next valid sample`() {
        val d = DayNightDebouncer()
        d.markApplied(DayNight.DAY)
        d.requestImmediate()
        assertEquals(APPLY_IMMEDIATE, d.onValid(DayNight.NIGHT))
    }

    @Test
    fun `reset returns to fresh session semantics`() {
        val d = DayNightDebouncer()
        d.markApplied(DayNight.DAY)
        d.reset()
        assertEquals(APPLY_IMMEDIATE, d.onValid(DayNight.DAY))
    }
}
