package com.andrerinas.openheadunit.geely.hvac

import org.junit.Assert.assertEquals
import org.junit.Test

class HvacPortFactoryTest {

    @Test
    fun `the adaptapi backend wins whenever it connects (D-007 write channel)`() {
        assertEquals(
            HvacPortFactory.Backend.ECARX,
            HvacPortFactory.select(
                ecarxAvailable = true, carAvailable = false, debugBuild = false, mockInstalled = false,
            )
        )
        assertEquals(
            HvacPortFactory.Backend.ECARX,
            HvacPortFactory.select(
                ecarxAvailable = true, carAvailable = true, debugBuild = true, mockInstalled = true,
            )
        )
    }

    @Test
    fun `android car is the read and fallback channel`() {
        assertEquals(
            HvacPortFactory.Backend.ANDROID_CAR,
            HvacPortFactory.select(
                ecarxAvailable = false, carAvailable = true, debugBuild = false, mockInstalled = false,
            )
        )
        assertEquals(
            HvacPortFactory.Backend.ANDROID_CAR,
            HvacPortFactory.select(
                ecarxAvailable = false, carAvailable = true, debugBuild = true, mockInstalled = true,
            )
        )
    }

    @Test
    fun `the mock app is debug-only`() {
        assertEquals(
            HvacPortFactory.Backend.MOCK,
            HvacPortFactory.select(
                ecarxAvailable = false, carAvailable = false, debugBuild = true, mockInstalled = true,
            )
        )
        assertEquals(
            HvacPortFactory.Backend.NONE,
            HvacPortFactory.select(
                ecarxAvailable = false, carAvailable = false, debugBuild = false, mockInstalled = true,
            )
        )
    }

    @Test
    fun `no backend when nothing is available`() {
        assertEquals(
            HvacPortFactory.Backend.NONE,
            HvacPortFactory.select(
                ecarxAvailable = false, carAvailable = false, debugBuild = true, mockInstalled = false,
            )
        )
    }
}
