package com.v2ray.ang.publicconfigs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
