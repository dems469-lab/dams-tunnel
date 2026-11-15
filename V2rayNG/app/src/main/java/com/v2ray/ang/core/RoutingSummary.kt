package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.RulesetItem

/**
 * Résumé lisible du routage effectivement appliqué.
 *
 * Sert au diagnostic affiché dans la console : sans lui, il est impossible de
 * savoir depuis un journal si le trafic chinois part encore en direct, ou si le
 * preset est bien celui qu'on croit.
 *
 * Classe pure : ni MMKV, ni assets, donc testable hors appareil.
 */
object RoutingSummary {

    data class Facts(
        val ruleCount: Int,
        val directChina: Boolean,
        val quicBlocked: Boolean,
        val catchAllTag: String?,
    )

    private const val TAG_PROXY = "proxy"
    private const val TAG_DIRECT = "direct"
    private const val TAG_BLOCK = "block"

    fun facts(rules: List<RulesetItem>?): Facts {
        val enabled = rules.orEmpty().filter { it.enabled }
        return Facts(
            ruleCount = enabled.size,
            directChina = enabled.any { rule ->
                rule.outboundTag == TAG_DIRECT && (
                    rule.ip.orEmpty().any { it == "geoip:cn" } ||
                        rule.domain.orEmpty().any { it == "geosite:cn" }
                    )
            },
            quicBlocked = enabled.any { rule ->
                rule.outboundTag == TAG_BLOCK && rule.port == "443" && rule.network == "udp"
            },
            catchAllTag = enabled.lastOrNull { isCatchAll(it) }?.outboundTag,
        )
    }

    /**
     * Vrai si la règle s'applique à tout le trafic : aucune condition de
     * destination (domaine, IP, processus, protocole) et un port plein.
     */
    private fun isCatchAll(rule: RulesetItem): Boolean {
        val noTarget = rule.domain.isNullOrEmpty() &&
            rule.ip.isNullOrEmpty() &&
            rule.process.isNullOrEmpty() &&
            rule.protocol.isNullOrEmpty()
        val allPorts = rule.port.isNullOrEmpty() || rule.port == "0-65535"
        val bothNetworks = rule.network.isNullOrEmpty() || rule.network == "tcp,udp"
        return noTarget && allPorts && bothNetworks
    }

    fun describe(rules: List<RulesetItem>?): String {
        val f = facts(rules)
        val default = when (f.catchAllTag) {
            TAG_PROXY -> "proxy"
            TAG_DIRECT -> "direct"
            TAG_BLOCK -> "blocage"
            else -> "premier sortant"
        }
        return "Routage : ${f.ruleCount} règles · Chine en direct : ${ouiNon(f.directChina)} · " +
            "QUIC bloqué : ${ouiNon(f.quicBlocked)} · défaut : $default"
    }

    private fun ouiNon(value: Boolean) = if (value) "oui" else "non"
}
