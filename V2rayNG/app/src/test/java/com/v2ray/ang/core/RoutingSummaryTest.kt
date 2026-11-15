package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.RulesetItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoutingSummaryTest {

    private fun rule(
        outboundTag: String,
        ip: List<String>? = null,
        domain: List<String>? = null,
        port: String? = null,
        network: String? = null,
        enabled: Boolean = true,
    ) = RulesetItem(
        ip = ip,
        domain = domain,
        outboundTag = outboundTag,
        port = port,
        network = network,
        enabled = enabled,
    )

    private val quicBlock = rule("block", port = "443", network = "udp")

    @Test
    fun `china whitelist is reported as bypassing china and having no catch-all`() {
        val facts = RoutingSummary.facts(
            listOf(
                quicBlock,
                rule("proxy", domain = listOf("geosite:google")),
                rule("direct", ip = listOf("geoip:private")),
                rule("direct", ip = listOf("geoip:cn")),
                rule("direct", domain = listOf("geosite:cn")),
            ),
        )

        assertEquals(5, facts.ruleCount)
        assertTrue(facts.directChina)
        assertTrue(facts.quicBlocked)
        assertNull(facts.catchAllTag, "Aucune règle ne couvre tout le trafic")
    }

    @Test
    fun `global preset is reported as proxying everything`() {
        val facts = RoutingSummary.facts(
            listOf(
                quicBlock,
                rule("direct", ip = listOf("geoip:private")),
                rule("direct", domain = listOf("geosite:private")),
                rule("proxy", port = "0-65535"),
            ),
        )

        assertFalse(facts.directChina)
        assertTrue(facts.quicBlocked)
        assertEquals("proxy", facts.catchAllTag)
    }

    @Test
    fun `disabled rules are ignored`() {
        val facts = RoutingSummary.facts(
            listOf(
                rule("direct", ip = listOf("geoip:cn"), enabled = false),
                rule("proxy", port = "0-65535", enabled = false),
            ),
        )

        assertEquals(0, facts.ruleCount)
        assertFalse(facts.directChina)
        assertNull(facts.catchAllTag)
    }

    @Test
    fun `missing rules do not crash and report no routing`() {
        assertEquals(0, RoutingSummary.facts(null).ruleCount)
        assertEquals(0, RoutingSummary.facts(emptyList()).ruleCount)
    }

    @Test
    fun `description is readable in the console`() {
        val global = RoutingSummary.describe(
            listOf(
                quicBlock,
                rule("direct", ip = listOf("geoip:private")),
                rule("proxy", port = "0-65535"),
            ),
        )
        assertEquals("Routage : 3 règles · Chine en direct : non · QUIC bloqué : oui · défaut : proxy", global)

        val legacy = RoutingSummary.describe(
            listOf(
                quicBlock,
                rule("direct", ip = listOf("geoip:cn")),
            ),
        )
        assertEquals(
            "Routage : 2 règles · Chine en direct : oui · QUIC bloqué : oui · défaut : premier sortant",
            legacy,
        )
    }
}
