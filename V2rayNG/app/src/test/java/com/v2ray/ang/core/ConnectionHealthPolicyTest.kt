package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionHealthPolicyTest {
    @Test
    fun `only three consecutive failed probes request recovery`() {
        val failures = ConsecutiveHealthFailures()
        assertFalse(failures.record(false))
        assertFalse(failures.record(false))
        assertTrue(failures.record(false))
    }

    @Test
    fun `successful probe resets failure streak`() {
        val failures = ConsecutiveHealthFailures()
        failures.record(false)
        failures.record(false)

        assertFalse(failures.record(true))
        assertFalse(failures.record(false))
    }

    @Test
    fun `reconnect delay is capped at thirty seconds`() {
        assertEquals(1_000L, reconnectBackoffMillis(0))
        assertEquals(2_000L, reconnectBackoffMillis(1))
        assertEquals(5_000L, reconnectBackoffMillis(2))
        assertEquals(10_000L, reconnectBackoffMillis(3))
        assertEquals(30_000L, reconnectBackoffMillis(4))
        assertEquals(30_000L, reconnectBackoffMillis(20))
    }

    @Test
    fun `failover waits for repeated failed reconnects`() {
        assertFalse(shouldTryFailoverAfterReconnect(2))
        assertTrue(shouldTryFailoverAfterReconnect(3))
        assertFalse(shouldTryFailoverAfterReconnect(4))
        assertTrue(shouldTryFailoverAfterReconnect(6))
    }
}
