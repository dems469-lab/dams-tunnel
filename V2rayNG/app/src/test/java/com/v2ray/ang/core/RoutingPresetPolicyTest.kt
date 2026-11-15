package com.v2ray.ang.core

import com.google.gson.Gson
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.RoutingType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class RoutingPresetPolicyTest {

    // region Choix du défaut

    @Test
    fun `default preset proxies everything instead of the china whitelist`() {
        assertEquals(RoutingType.GLOBAL, RoutingPresetPolicy.DEFAULT_PRESET)
        assertEquals(RoutingType.WHITE, RoutingPresetPolicy.LEGACY_DEFAULT_PRESET)
    }

    // endregion

    // region Reprise de l'ancien défaut

    private fun rule(
        outboundTag: String,
        ip: List<String>? = null,
        domain: List<String>? = null,
        remarks: String = "",
    ) = RulesetItem(
        remarks = remarks,
        ip = ip,
        domain = domain,
        outboundTag = outboundTag,
    )

    @Test
    fun `untouched legacy preset is recognised`() {
        val legacy = listOf(
            rule("block", remarks = "udp443"),
            rule("direct", ip = listOf("geoip:private")),
            rule("direct", domain = listOf("geosite:cn")),
        )
        val stored = listOf(
            rule("block", remarks = "udp443"),
            rule("direct", ip = listOf("geoip:private")),
            rule("direct", domain = listOf("geosite:cn")),
        )

        assertTrue(RoutingPresetPolicy.isLegacyDefault(stored, legacy))
    }

    @Test
    fun `renamed rule is still the untouched legacy preset`() {
        // Changer un libellé ne change pas le routage : ce n'est pas une
        // configuration volontaire, la reprise doit encore s'appliquer.
        val legacy = listOf(rule("direct", domain = listOf("geosite:cn"), remarks = "绕过中国域名"))
        val stored = listOf(rule("direct", domain = listOf("geosite:cn"), remarks = "Chine direct"))

        assertTrue(RoutingPresetPolicy.isLegacyDefault(stored, legacy))
    }

    @Test
    fun `edited rule is not overwritten`() {
        val legacy = listOf(rule("direct", domain = listOf("geosite:cn")))
        val stored = listOf(rule("proxy", domain = listOf("geosite:cn")))

        assertFalse(RoutingPresetPolicy.isLegacyDefault(stored, legacy))
    }

    @Test
    fun `added or removed rule is not overwritten`() {
        val legacy = listOf(rule("direct", domain = listOf("geosite:cn")))

        assertFalse(RoutingPresetPolicy.isLegacyDefault(legacy + rule("proxy", domain = listOf("geosite:google")), legacy))
        assertFalse(RoutingPresetPolicy.isLegacyDefault(listOf(rule("direct", domain = listOf("geosite:cn")), rule("proxy")), legacy))
    }

    @Test
    fun `missing or empty rules are not migrated`() {
        val legacy = listOf(rule("direct", domain = listOf("geosite:cn")))

        assertFalse(RoutingPresetPolicy.isLegacyDefault(null, legacy))
        assertFalse(RoutingPresetPolicy.isLegacyDefault(emptyList(), legacy))
        assertFalse(RoutingPresetPolicy.isLegacyDefault(legacy, null))
    }

    // endregion

    // region Contenu des presets livrés

    @Test
    fun `shipped global preset routes everything through the proxy`() {
        val rules = readRules("custom_routing_global")

        assertTrue(
            rules.none { it.outboundTag == "direct" && it.ip?.contains("geoip:cn") == true },
            "Le preset Mondial ne doit pas laisser les IP chinoises en direct",
        )
        assertTrue(
            rules.none { it.outboundTag == "direct" && it.domain?.contains("geosite:cn") == true },
            "Le preset Mondial ne doit pas laisser les domaines chinois en direct",
        )
        assertEquals("proxy", rules.last().outboundTag, "La dernière règle doit proxyfier le reste")
        assertEquals("0-65535", rules.last().port)
        assertTrue(
            rules.any { it.outboundTag == "direct" && it.ip?.contains("geoip:private") == true },
            "Le réseau local doit rester joignable en direct",
        )
    }

    @Test
    fun `shipped china whitelist still bypasses chinese traffic - the reason it is no longer the default`() {
        val rules = readRules("custom_routing_white")

        assertTrue(rules.any { it.outboundTag == "direct" && it.ip?.contains("geoip:cn") == true })
        assertTrue(rules.any { it.outboundTag == "direct" && it.domain?.contains("geosite:cn") == true })
    }

    // endregion

    private fun readRules(name: String): List<RulesetItem> {
        val file = locateAsset(name)
        val rules = Gson().fromJson(file.readText(), Array<RulesetItem>::class.java)
        return rules.toList()
    }

    /**
     * Retrouve un asset du module `app` quel que soit le répertoire de travail
     * utilisé par Gradle pour les tests unitaires.
     */
    private fun locateAsset(name: String): File {
        val relativePaths = listOf(
            "src/main/assets/$name",
            "app/src/main/assets/$name",
            "V2rayNG/app/src/main/assets/$name",
        )
        val roots = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(6)
            .toList()

        roots.forEach { root ->
            relativePaths.forEach { relative ->
                val candidate = File(root, relative)
                if (candidate.isFile) return candidate
            }
        }
        throw AssertionError("Asset '$name' introuvable depuis ${File(".").absolutePath}")
    }
}
