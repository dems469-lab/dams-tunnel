package com.v2ray.ang.publicconfigs

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicServerCatalogTest {
    private fun vless(
        name: String,
        host: String = "node.example",
        network: String = "tcp",
        delay: Long = 0L,
        updated: Long = 10L,
    ) = PublicServerInput(
        guid = name,
        profile = ProfileItem.create(EConfigType.VLESS).apply {
            remarks = name
            server = host
            serverPort = "443"
            password = "123e4567-e89b-12d3-a456-426614174000"
            this.network = network
            security = "tls"
            if (network == "ws") {
                path = "/tunnel"
                this.host = "cdn.example"
            }
            sni = "cdn.example"
        },
        sourceName = "Source A",
        sourceUpdatedAt = updated,
        delayMillis = delay,
    )

    @Test
    fun `protocol counters include only supported server protocols`() {
        val inputs = listOf(
            vless("one"),
            vless("two", host = "other.example"),
            vless("three", network = "ws"),
        )
        val catalog = buildPublicServerCatalog(inputs, PublicServerFilters())
        assertEquals(3, catalog.totalProfiles)
        assertEquals(3, catalog.protocolCounts[PublicProtocol.VLESS])
        assertFalse(PublicProtocol.entries.any { it.name == "SSH" })
    }

    @Test
    fun `unsupported aggregate profile types are not shown as server protocols`() {
        val aggregate = PublicServerInput(
            guid = "rules",
            profile = ProfileItem.create(EConfigType.POLICYGROUP).apply { remarks = "Rules group" },
            sourceName = "Provider",
            sourceUpdatedAt = 0L,
            delayMillis = 0L,
        )
        val catalog = buildPublicServerCatalog(listOf(aggregate), PublicServerFilters())
        assertEquals(0, catalog.totalProfiles)
        assertTrue(catalog.groups.isEmpty())
        assertTrue(catalog.protocolCounts.isEmpty())
    }

    @Test
    fun `search matches name source host protocol and country text supplied by source`() {
        val input = vless("Burkina · VLESS relay").copy(sourceName = "Provider West")
        assertEquals(1, buildPublicServerCatalog(listOf(input), PublicServerFilters(query = "Burkina")).totalProfiles)
        assertEquals(1, buildPublicServerCatalog(listOf(input), PublicServerFilters(query = "Provider")).totalProfiles)
        assertEquals(1, buildPublicServerCatalog(listOf(input), PublicServerFilters(query = "node.example")).totalProfiles)
        assertEquals(1, buildPublicServerCatalog(listOf(input), PublicServerFilters(query = "VLESS")).totalProfiles)
    }

    @Test
    fun `filters distinguish completeness availability and whether a test was performed`() {
        val entries = listOf(
            vless("responded", delay = 73L),
            vless("not-responded", delay = -1L),
            vless("not-tested", delay = 0L),
            vless("incomplete").copy(profile = ProfileItem.create(EConfigType.VLESS).apply {
                remarks = "incomplete"
                server = "node.example"
                serverPort = "invalid"
                password = "not-a-uuid"
            }),
        )
        assertEquals(1, buildPublicServerCatalog(entries, PublicServerFilters(availability = PublicAvailability.RESPONDED)).totalProfiles)
        assertEquals(1, buildPublicServerCatalog(entries, PublicServerFilters(availability = PublicAvailability.NO_RESPONSE)).totalProfiles)
        assertEquals(2, buildPublicServerCatalog(entries, PublicServerFilters(availability = PublicAvailability.UNKNOWN)).totalProfiles)
        assertEquals(2, buildPublicServerCatalog(entries, PublicServerFilters(verification = PublicVerification.TESTED)).totalProfiles)
        assertEquals(1, buildPublicServerCatalog(entries, PublicServerFilters(completeness = PublicCompleteness.INVALID)).totalProfiles)
    }

    @Test
    fun `transport dependent fields are assessed only when applicable`() {
        val tcp = assessPublicProfile(vless("tcp").profile)
        val ws = assessPublicProfile(vless("ws", network = "ws").profile)
        assertEquals(PublicCompleteness.COMPLETE, tcp.completeness)
        assertEquals(PublicCompleteness.COMPLETE, ws.completeness)
        val reality = vless("reality").profile.apply {
            security = "reality"
            sni = null
            publicKey = "public-key"
        }
        assertTrue("sni" in assessPublicProfile(reality).missingFieldKeys)
    }

    @Test
    fun `sort modes are independent and latency is never synthesized`() {
        val entries = listOf(
            vless("slow", delay = 120L, updated = 30L),
            vless("fast", host = "fast.example", delay = 35L, updated = 20L),
            vless("recent", host = "recent.example", updated = 40L),
        )
        val latency = buildPublicServerCatalog(entries, PublicServerFilters(sort = PublicSort.LATENCY))
        val latest = buildPublicServerCatalog(entries, PublicServerFilters(sort = PublicSort.SOURCE_UPDATED))
        assertEquals("fast", latency.groups.first().variants.single().name)
        assertEquals("recent", latest.groups.first().variants.single().name)
        assertEquals(null, latency.groups.last().bestMeasuredLatency)
    }

    @Test
    fun `equivalent profiles are grouped for display without removing variants`() {
        val first = vless("same name", delay = 80L)
        val second = first.copy(guid = "second-guid", sourceName = "Provider B", delayMillis = 55L)
        val catalog = buildPublicServerCatalog(listOf(first, second), PublicServerFilters())
        assertEquals(2, catalog.totalProfiles)
        assertEquals(1, catalog.groups.size)
        assertEquals(setOf("same name", "second-guid"), catalog.groups.single().variants.map { it.guid }.toSet())
        assertEquals(55L, catalog.groups.single().bestMeasuredLatency)
    }

    @Test
    fun `details and search model never contain uuid or password values`() {
        val profile = ProfileItem.create(EConfigType.TROJAN).apply {
            remarks = "Trojan public"
            server = "trojan.example"
            serverPort = "443"
            password = "secret-password"
            security = "tls"
        }
        val item = PublicServerInput("trojan-id", profile, "Provider", 10L, 0L, "provider-id")
        val variant = buildPublicServerCatalog(listOf(item), PublicServerFilters()).groups.single().variants.single()
        assertNotNull(variant.details)
        val visible = variant.details.toString() + variant.searchableText
        assertFalse(visible.contains("secret-password"))
    }

    @Test
    fun `public labels redact credential shaped values`() {
        val label = redactPublicLabel("trojan://alice:secret@example.org?token=abc 123e4567-e89b-12d3-a456-426614174000")
        assertFalse(label.contains("secret"))
        assertFalse(label.contains("token=abc"))
        assertFalse(label.contains("123e4567-e89b-12d3-a456-426614174000"))
    }
}
