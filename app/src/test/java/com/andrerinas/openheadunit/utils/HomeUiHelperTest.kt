package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiHelperTest {

    @Test
    fun isEnabledReturnsChosenWhenDisconnected() {
        assertFalse(HomeUiHelper.isEnabled(chosen = false, isConnected = false))
        assertTrue(HomeUiHelper.isEnabled(chosen = true, isConnected = false))
    }

    @Test
    fun isEnabledReturnsTrueWhenConnectedEvenIfNotChosen() {
        assertTrue(HomeUiHelper.isEnabled(chosen = false, isConnected = true))
        assertTrue(HomeUiHelper.isEnabled(chosen = true, isConnected = true))
    }
}
