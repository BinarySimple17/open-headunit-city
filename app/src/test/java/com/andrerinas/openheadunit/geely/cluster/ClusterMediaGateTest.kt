package com.andrerinas.openheadunit.geely.cluster

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dependency gate of the cluster card (F013): the native "AA now playing on media
 * session" switch is the master switch, ours only redirects on top of it — nothing is
 * published unless BOTH are on.
 */
class ClusterMediaGateTest {

    @Test
    fun `both switches on - the gate is open`() {
        assertTrue(ClusterMediaGate.isOpen(nativeSync = true, ours = true))
    }

    @Test
    fun `native off means nothing is ever published - whatever our switch says`() {
        assertFalse(ClusterMediaGate.isOpen(nativeSync = false, ours = false))
        assertFalse(ClusterMediaGate.isOpen(nativeSync = false, ours = true))
    }

    @Test
    fun `our switch off alone closes the gate`() {
        assertFalse(ClusterMediaGate.isOpen(nativeSync = true, ours = false))
    }
}
