package com.v2ray.ang.publicconfigs

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FreeServerLatencyTest {
    @Test
    fun `unset and cleared latency are not presented as measured`() {
        assertFalse(hasMeasuredFreeServerLatency(-1L))
        assertFalse(hasMeasuredFreeServerLatency(0L))
    }

    @Test
    fun `positive latency is presented as measured`() {
        assertTrue(hasMeasuredFreeServerLatency(1L))
        assertTrue(hasMeasuredFreeServerLatency(84L))
    }
}
