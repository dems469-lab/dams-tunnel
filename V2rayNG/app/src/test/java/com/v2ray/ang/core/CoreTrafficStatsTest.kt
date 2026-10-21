package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.TrafficSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreTrafficStatsTest {
    @Test
    fun extractsEveryOutboundTagFromTheGeneratedConfiguration() {
        assertEquals(
            setOf("proxy", "direct", "proxy-chain-1", "balancer"),
            outboundTagsFromConfig(
                """{"outbounds":[{"tag":"proxy"},{"tag":"direct"},{"tag":"proxy-chain-1"},{"tag":"balancer"}]}"""
            ),
        )
    }

    @Test
    fun invalidConfigurationHasNoTags() {
        assertTrue(outboundTagsFromConfig("not-json").isEmpty())
    }

    @Test
    fun emptyStatsAreUnavailableRatherThanZeroTraffic() {
        assertNull(trafficSnapshotFromStats(emptyList(), 1_000, TrafficSnapshot()))
    }

    @Test
    fun aggregatesAllTagsAndPreservesSessionTotals() {
        val snapshot = trafficSnapshotFromStats(
            listOf(
                OutboundTrafficStat("proxy-chain-1", AppConfig.UPLINK, 200),
                OutboundTrafficStat("proxy-chain-2", AppConfig.DOWNLINK, 600),
            ),
            1_000,
            TrafficSnapshot(uplinkTotalBytes = 50, downlinkTotalBytes = 70),
        )

        assertTrue(snapshot?.available == true)
        assertEquals(200, snapshot?.uplinkBytesPerSecond)
        assertEquals(600, snapshot?.downlinkBytesPerSecond)
        assertEquals(250, snapshot?.uplinkTotalBytes)
        assertEquals(670, snapshot?.downlinkTotalBytes)
    }

    @Test
    fun zeroValuesFromSuccessfulStatsRemainAvailable() {
        val snapshot = trafficSnapshotFromStats(
            listOf(OutboundTrafficStat("proxy", AppConfig.UPLINK, 0)),
            1_000,
            TrafficSnapshot(),
        )

        assertTrue(snapshot?.available == true)
        assertEquals(0, snapshot?.uplinkBytesPerSecond)
        assertFalse(snapshot?.available == false)
    }
}
