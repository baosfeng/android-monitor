package com.bsfeng.monitor.domain.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPolicyTest {

    @Test
    fun `thresholdBytes`() {
        assertEquals(0L, PairingPolicy.thresholdBytes(0))
        assertEquals(10L * 1024 * 1024, PairingPolicy.thresholdBytes(10))
        assertEquals(0L, PairingPolicy.thresholdBytes(-5))
    }

    @Test
    fun `window active and remaining`() {
        assertTrue(PairingPolicy.isWindowActive(100L, 99L))
        assertFalse(PairingPolicy.isWindowActive(100L, 100L))
        assertFalse(PairingPolicy.isWindowActive(100L, 101L))
        assertEquals(1L, PairingPolicy.remainingMs(100L, 99L))
        assertEquals(0L, PairingPolicy.remainingMs(100L, 100L))
        assertEquals(0L, PairingPolicy.remainingMs(100L, 200L))
    }

    @Test
    fun `isAutoApproved`() {
        assertTrue(PairingPolicy.isAutoApproved(200L, 0, 1000L, 100L))
        assertFalse(PairingPolicy.isAutoApproved(0L, 0, 10L, 100L))
        assertFalse(PairingPolicy.isAutoApproved(0L, 10, -1L, 100L))
        assertTrue(PairingPolicy.isAutoApproved(0L, 10, 10L * 1024 * 1024, 100L))
        assertTrue(PairingPolicy.isAutoApproved(0L, 10, 5L, 100L))
        assertFalse(PairingPolicy.isAutoApproved(0L, 10, 11L * 1024 * 1024, 100L))
    }

    @Test
    fun `constants`() {
        assertEquals(0, PairingPolicy.THRESHOLD_OFF_MB)
        assertEquals(listOf(5, 10, 30), PairingPolicy.WINDOW_MINUTES)
        assertEquals(listOf(0, 10, 50, 100), PairingPolicy.THRESHOLD_OPTIONS_MB)
    }
}
