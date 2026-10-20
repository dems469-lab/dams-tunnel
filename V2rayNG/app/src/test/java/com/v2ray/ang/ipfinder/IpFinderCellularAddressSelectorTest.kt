package com.v2ray.ang.ipfinder

import java.net.InetAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IpFinderCellularAddressSelectorTest {
    @Test
    fun prefersCellularIpv4WhenAndroidListsIpv6First() {
        val ipv6 = InetAddress.getByName("2001:db8::1")
        val ipv4 = InetAddress.getByName("10.204.5.5")

        assertEquals("10.204.5.5", IpFinderCellularAddressSelector.select(listOf(ipv6, ipv4)))
    }

    @Test
    fun returnsEveryUsableAddressWithIpv4First() {
        val ipv6 = InetAddress.getByName("2001:db8::1")
        val ipv4First = InetAddress.getByName("10.204.5.5")
        val ipv4Second = InetAddress.getByName("10.205.6.7")

        assertEquals(
            listOf("10.204.5.5", "10.205.6.7", "2001:db8:0:0:0:0:0:1"),
            IpFinderCellularAddressSelector.selectAll(listOf(ipv6, ipv4First, ipv4Second))
        )
    }

    @Test
    fun fallsBackToIpv6WhenNoIpv4IsAvailable() {
        assertEquals(
            "2001:db8:0:0:0:0:0:1",
            IpFinderCellularAddressSelector.select(listOf(InetAddress.getByName("2001:db8::1")))
        )
    }

    @Test
    fun ignoresLoopbackAndLinkLocalAddresses() {
        val linkLocal = InetAddress.getByName("169.254.1.2")

        assertNull(IpFinderCellularAddressSelector.select(listOf(InetAddress.getLoopbackAddress(), linkLocal)))
    }
}
