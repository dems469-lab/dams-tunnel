package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficStatsParserTest {
    @Test
    fun parsesValidStatsAndIgnoresMalformedEntries() {
        val result = TrafficStatsParser.parse("proxy,uplink,120;proxy,downlink,240;bad;direct,unknown,3;direct,uplink,nope")

        assertEquals(
            listOf(
                com.v2ray.ang.dto.OutboundTrafficStat("proxy", "uplink", 120),
                com.v2ray.ang.dto.OutboundTrafficStat("proxy", "downlink", 240),
            ),
            result,
        )
    }
}
