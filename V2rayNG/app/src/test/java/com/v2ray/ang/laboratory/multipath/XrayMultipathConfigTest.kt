package com.v2ray.ang.laboratory.multipath

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XrayMultipathConfigTest {

    private val baseConfig = """
        {
          "log": {"loglevel": "warning"},
          "inbounds": [
            {"tag": "tun", "port": 0, "protocol": "tun", "settings": {"address": ["10.0.0.1/24"]}}
          ],
          "outbounds": [
            {
              "tag": "proxy",
              "protocol": "vless",
              "settings": {"vnext": [{"address": "a.example.com", "port": 443, "users": [{"id": "11111111-1111-1111-1111-111111111111", "encryption": "none"}]}]},
              "streamSettings": {"network": "ws", "security": "tls"}
            },
            {"tag": "direct", "protocol": "freedom"},
            {"tag": "block", "protocol": "blackhole"}
          ],
          "routing": {
            "domainStrategy": "AsIs",
            "rules": [
              {"type": "field", "outboundTag": "proxy", "domain": ["example.com"]}
            ]
          }
        }
    """.trimIndent()

    private val secondaryB = """
        {
          "tag": "ignored",
          "protocol": "vmess",
          "settings": {"vnext": [{"address": "b.example.com", "port": 443, "users": [{"id": "22222222-2222-2222-2222-222222222222", "alterId": 0}]}]},
          "streamSettings": {"network": "grpc", "security": "tls"}
        }
    """.trimIndent()

    private val secondaryC = """
        {
          "tag": "ignored",
          "protocol": "trojan",
          "settings": {"servers": [{"address": "c.example.com", "port": 443, "password": "secret"}]},
          "streamSettings": {"network": "tcp", "security": "tls"}
        }
    """.trimIndent()

    private fun secondaries(): Map<PathRole, String> = mapOf(
        PathRole.SECONDARY_B to secondaryB,
        PathRole.SECONDARY_C to secondaryC,
    )

    private fun injected(
        selector: List<String> = XrayMultipathConfig.allTags,
    ): XrayMultipathConfig.Injection = requireNotNull(inject(selector)) {
        "the three-path injection should succeed"
    }

    private fun inject(
        selector: List<String> = XrayMultipathConfig.allTags,
    ): XrayMultipathConfig.Injection? = XrayMultipathConfig.inject(
        baseConfigJson = baseConfig,
        secondaryOutbounds = secondaries(),
        selector = selector,
        probeUrl = MultipathSettings.DEFAULT_PROBE_URL,
        observatoryIntervalSeconds = 10,
    )

    @Test
    fun `inject adds three independent paths and keeps direct and block`() {
        val result = injected()
        val root = JsonParser.parseString(result.configJson).asJsonObject
        val outbounds = root.getAsJsonArray("outbounds")
        val tags = outbounds.map { it.asJsonObject.get("tag").asString }

        assertEquals(5, tags.size)
        assertTrue(tags.containsAll(listOf("path-a", "path-b", "path-c", "direct", "block")))
        // Le premier outbound reste le défaut Xray : c'est le chemin principal.
        assertEquals("path-a", tags.first())

        assertEquals(3, result.outboundJsons.size)
        assertNotNull(result.outboundJsons[PathRole.PRIMARY])
        assertNotNull(result.outboundJsons[PathRole.SECONDARY_B])
        assertNotNull(result.outboundJsons[PathRole.SECONDARY_C])
    }

    @Test
    fun `inject rewrites every reference to the historical primary tag`() {
        val result = injected()
        val root = JsonParser.parseString(result.configJson).asJsonObject
        val rules = root.getAsJsonObject("routing").getAsJsonArray("rules")

        val userRule = rules.first { it.asJsonObject.has("domain") }.asJsonObject
        assertEquals("path-a", userRule.get("outboundTag").asString)

        val catchAll = rules.first { it.asJsonObject.has("balancerTag") }.asJsonObject
        assertEquals(XrayMultipathConfig.BALANCER_TAG, catchAll.get("balancerTag").asString)
        assertEquals("tcp,udp", catchAll.get("network").asString)
    }

    @Test
    fun `inject configures a least ping balancer and an observatory`() {
        val result = injected()
        val root = JsonParser.parseString(result.configJson).asJsonObject
        val balancer = root.getAsJsonObject("routing")
            .getAsJsonArray("balancers")
            .first { it.asJsonObject.get("tag").asString == XrayMultipathConfig.BALANCER_TAG }
            .asJsonObject

        assertEquals("path-a", balancer.get("fallbackTag").asString)
        assertEquals("leastPing", balancer.getAsJsonObject("strategy").get("type").asString)
        assertEquals(
            listOf("path-a", "path-b", "path-c"),
            balancer.getAsJsonArray("selector").map { it.asString },
        )

        val observatory = root.getAsJsonObject("observatory")
        assertNotNull(observatory)
        assertEquals("10s", observatory.get("probeInterval").asString)
        assertTrue(observatory.get("enableConcurrency").asBoolean)
        assertEquals(
            listOf("path-a", "path-b", "path-c"),
            observatory.getAsJsonArray("subjectSelector").map { it.asString },
        )
    }

    @Test
    fun `inject honours a restricted selector`() {
        val result = injected(listOf("path-a"))
        val root = JsonParser.parseString(result.configJson).asJsonObject
        val balancer = root.getAsJsonObject("routing")
            .getAsJsonArray("balancers")
            .first()
            .asJsonObject
        assertEquals(listOf("path-a"), balancer.getAsJsonArray("selector").map { it.asString })
        assertTrue(XrayMultipathConfig.isMultipathConfigValid(result.configJson))
    }

    @Test
    fun `inject refuses a configuration that is already injected`() {
        val first = injected()
        val second = XrayMultipathConfig.inject(
            baseConfigJson = first.configJson,
            secondaryOutbounds = secondaries(),
            selector = XrayMultipathConfig.allTags,
            probeUrl = MultipathSettings.DEFAULT_PROBE_URL,
            observatoryIntervalSeconds = 10,
        )
        assertNull(second)
    }

    @Test
    fun `inject refuses a base without primary outbound`() {
        val withoutProxy = """{"outbounds":[{"tag":"direct","protocol":"freedom"}]}"""
        val result = XrayMultipathConfig.inject(
            baseConfigJson = withoutProxy,
            secondaryOutbounds = secondaries(),
            selector = XrayMultipathConfig.allTags,
            probeUrl = MultipathSettings.DEFAULT_PROBE_URL,
            observatoryIntervalSeconds = 10,
        )
        assertNull(result)
    }

    @Test
    fun `inject refuses an invalid secondary outbound`() {
        val result = XrayMultipathConfig.inject(
            baseConfigJson = baseConfig,
            secondaryOutbounds = mapOf(
                PathRole.SECONDARY_B to """{"protocol":"freedom"}""",
                PathRole.SECONDARY_C to secondaryC,
            ),
            selector = XrayMultipathConfig.allTags,
            probeUrl = MultipathSettings.DEFAULT_PROBE_URL,
            observatoryIntervalSeconds = 10,
        )
        assertNull(result)
    }

    @Test
    fun `withSelector rewrites only the balancer selector`() {
        val injected = injected()
        val updated = requireNotNull(
            XrayMultipathConfig.withSelector(injected.configJson, listOf("path-b", "path-c")),
        )
        val root = JsonParser.parseString(updated).asJsonObject
        val balancer = root.getAsJsonObject("routing")
            .getAsJsonArray("balancers")
            .first()
            .asJsonObject
        assertEquals(listOf("path-b", "path-c"), balancer.getAsJsonArray("selector").map { it.asString })
        assertEquals(5, root.getAsJsonArray("outbounds").size())
        assertTrue(XrayMultipathConfig.isMultipathConfigValid(updated))
    }

    @Test
    fun `withSelector falls back to every path for an empty list`() {
        val injected = injected()
        val updated = requireNotNull(XrayMultipathConfig.withSelector(injected.configJson, emptyList()))
        val root = JsonParser.parseString(updated).asJsonObject
        val selector = root.getAsJsonObject("routing")
            .getAsJsonArray("balancers")
            .first()
            .asJsonObject
            .getAsJsonArray("selector")
            .map { it.asString }
        assertEquals(listOf("path-a", "path-b", "path-c"), selector)
    }

    @Test
    fun `validation rejects a config without catch all rule`() {
        val result = injected()
        val root = JsonParser.parseString(result.configJson).asJsonObject
        val rules = root.getAsJsonObject("routing").getAsJsonArray("rules")
        val index = (0 until rules.size()).first {
            rules[it].asJsonObject.has("balancerTag")
        }
        rules.remove(index)
        assertFalse(XrayMultipathConfig.isMultipathConfigValid(root.toString()))
    }

    @Test
    fun `validation rejects malformed json`() {
        assertFalse(XrayMultipathConfig.isMultipathConfigValid("not json"))
    }
}
