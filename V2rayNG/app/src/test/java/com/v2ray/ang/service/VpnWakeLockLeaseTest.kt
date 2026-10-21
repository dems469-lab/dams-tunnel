package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VpnWakeLockLeaseTest {
    @Test
    fun repeatedAcquireAndReleaseAreBalanced() {
        var acquired = 0
        var released = 0
        val lease = VpnWakeLockLease({ acquired++ }, { released++ })

        lease.acquire()
        lease.acquire()
        lease.release()
        lease.release()

        assertEquals(1, acquired)
        assertEquals(1, released)
    }

    @Test
    fun leaseCanBeAcquiredAgainAfterRelease() {
        var acquired = 0
        var released = 0
        val lease = VpnWakeLockLease({ acquired++ }, { released++ })

        lease.acquire()
        lease.release()
        lease.acquire()
        lease.release()

        assertEquals(2, acquired)
        assertEquals(2, released)
    }
}
