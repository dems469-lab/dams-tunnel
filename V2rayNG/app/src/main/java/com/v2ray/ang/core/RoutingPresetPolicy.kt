package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.RoutingType

/**
 * Choix du preset de routage par défaut et reprise de l'ancien défaut.
 *
 * Le défaut historique ([LEGACY_DEFAULT_PRESET]) est la liste d'autorisation
 * Chine : elle envoie `geoip:cn` et `geosite:cn` en direct, donc les services
 * chinois (DeepSeek, par exemple) ne passent jamais par le tunnel et gardent
 * l'IP réelle. Le défaut est désormais [DEFAULT_PRESET], qui fait passer tout
 * le trafic par le proxy et ne laisse en direct que le réseau local.
 *
 * Cette classe est volontairement pure : elle ne lit ni MMKV ni les assets,
 * pour rester testable hors appareil.
 */
object RoutingPresetPolicy {

    /** Preset installé quand aucune règle de routage n'existe encore. */
    val DEFAULT_PRESET: RoutingType = RoutingType.GLOBAL

    /** Preset installé par défaut jusqu'à la 1.10.2-lab incluse. */
    val LEGACY_DEFAULT_PRESET: RoutingType = RoutingType.WHITE

    /**
     * Vrai si les règles stockées sont exactement le preset fourni, à l'ordre
     * et aux valeurs près.
     *
     * Sert à ne reprendre que les installations restées sur le défaut
     * d'origine, jamais une configuration modifiée par la personne.
     */
    fun isLegacyDefault(
        stored: List<RulesetItem>?,
        legacyPreset: List<RulesetItem>?,
    ): Boolean {
        if (stored.isNullOrEmpty() || legacyPreset == null) return false
        if (stored.size != legacyPreset.size) return false
        return stored.map { it.signature() } == legacyPreset.map { it.signature() }
    }

    /**
     * Signature fonctionnelle d'une règle.
     *
     * `remarks` et `id` sont ignorés : ce sont des étiquettes d'affichage, les
     * modifier ne change pas le comportement du tunnel. Les faire entrer dans
     * la comparaison empêcherait de reconnaître un défaut intact après un
     * simple changement de libellé.
     */
    private fun RulesetItem.signature(): String = listOf(
        enabled.toString(),
        locked.toString(),
        outboundTag,
        port.orEmpty(),
        network.orEmpty(),
        ip?.joinToString(",").orEmpty(),
        domain?.joinToString(",").orEmpty(),
        process?.joinToString(",").orEmpty(),
        protocol?.joinToString(",").orEmpty(),
    ).joinToString("|")
}
