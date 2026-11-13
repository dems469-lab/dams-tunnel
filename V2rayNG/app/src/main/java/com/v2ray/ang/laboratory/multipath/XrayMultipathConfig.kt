package com.v2ray.ang.laboratory.multipath

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Injection pure des trois chemins dans une configuration Xray existante.
 *
 * Aucune configuration n'est créée de zéro : le laboratoire part de la
 * configuration complète du serveur principal (inbounds, DNS, fakeDNS, règles
 * utilisateur, réglages de transport) puis y ajoute :
 *
 * 1. le renommage de l'outbound `proxy` en `path-a` et la réécriture de toutes
 *    ses références (règles de routage, DNS, balancers, dialerProxy) ;
 * 2. deux outbounds indépendants `path-b` et `path-c` ;
 * 3. un balancer `multipath-main` en `leastPing` avec repli sur `path-a` ;
 * 4. un observatoire Xray qui sonde les trois chemins ;
 * 5. une règle attrape-tout routant les nouvelles connexions vers le balancer.
 *
 * La classe est volontairement sans dépendance Android pour être testable sur
 * JVM. Elle ne touche jamais aux règles utilisateur existantes : celles-ci
 * restent prioritaires (le premier match gagne, comme dans Xray).
 */
internal object XrayMultipathConfig {

    const val PRIMARY_TAG = "path-a"
    const val SECONDARY_B_TAG = "path-b"
    const val SECONDARY_C_TAG = "path-c"
    const val BALANCER_TAG = "multipath-main"

    private const val LEGACY_PRIMARY_TAG = "proxy"
    private const val STRATEGY_LEAST_PING = "leastPing"

    val allTags: List<String> = listOf(PRIMARY_TAG, SECONDARY_B_TAG, SECONDARY_C_TAG)

    data class Injection(
        /** Configuration complète prête pour `CoreController.startLoop`. */
        val configJson: String,
        /** Outbound autonome de chaque chemin, pour les sondes de latence. */
        val outboundJsons: Map<PathRole, String>,
    )

    /**
     * @param baseConfigJson configuration complète du serveur principal
     * @param secondaryOutbounds outbound `path-b` / `path-c` (JSON autonome)
     * @param selector sous-ensemble de tags réellement routé
     */
    fun inject(
        baseConfigJson: String,
        secondaryOutbounds: Map<PathRole, String>,
        selector: List<String>,
        probeUrl: String,
        observatoryIntervalSeconds: Int,
    ): Injection? {
        val root = parseObject(baseConfigJson) ?: return null
        val outbounds = root.getAsJsonArray("outbounds") ?: return null

        if (outbounds.tagExists(PRIMARY_TAG) ||
            outbounds.tagExists(SECONDARY_B_TAG) ||
            outbounds.tagExists(SECONDARY_C_TAG)
        ) {
            // Configuration déjà injectée : refus explicite plutôt qu'un doublon.
            return null
        }

        val primary = outbounds.objects().firstOrNull { it.tagOrNull() == LEGACY_PRIMARY_TAG }
            ?: return null
        primary.addProperty("tag", PRIMARY_TAG)
        rewritePrimaryReferences(root, LEGACY_PRIMARY_TAG, PRIMARY_TAG)

        val secondaryRoles = listOf(
            PathRole.SECONDARY_B to SECONDARY_B_TAG,
            PathRole.SECONDARY_C to SECONDARY_C_TAG,
        )
        for ((role, tag) in secondaryRoles) {
            val raw = secondaryOutbounds[role] ?: return null
            val outbound = parseObject(raw) ?: return null
            val protocol = outbound.stringOrNull("protocol") ?: return null
            if (protocol == "freedom" || protocol == "blackhole" || protocol == "dns") return null
            if (outbound.getAsJsonObject("settings") == null) return null
            outbound.addProperty("tag", tag)
            outbounds.add(outbound)
        }

        val effectiveSelector = selector
            .filter { it in allTags }
            .distinct()
            .ifEmpty { allTags }

        val routing = root.getAsJsonObject("routing")
            ?: JsonObject().also { root.add("routing", it) }

        val balancers = routing.getAsJsonArray("balancers")
            ?: JsonArray().also { routing.add("balancers", it) }
        removeWhere(balancers) { it.stringOrNull("tag") == BALANCER_TAG }
        balancers.add(
            JsonObject().apply {
                addProperty("tag", BALANCER_TAG)
                add("selector", JsonArray().apply { effectiveSelector.forEach { add(it) } })
                addProperty("fallbackTag", PRIMARY_TAG)
                add("strategy", JsonObject().apply { addProperty("type", STRATEGY_LEAST_PING) })
            }
        )

        root.add(
            "observatory",
            JsonObject().apply {
                add("subjectSelector", JsonArray().apply { allTags.forEach { add(it) } })
                addProperty("probeUrl", probeUrl)
                addProperty("probeInterval", "${observatoryIntervalSeconds.coerceIn(5, 60)}s")
                addProperty("enableConcurrency", true)
            }
        )

        val rules = routing.getAsJsonArray("rules")
            ?: JsonArray().also { routing.add("rules", it) }
        // Idempotence : une seule règle attrape-tout vers ce balancer.
        removeWhere(rules) { it.stringOrNull("balancerTag") == BALANCER_TAG }
        rules.add(
            JsonObject().apply {
                addProperty("type", "field")
                addProperty("network", "tcp,udp")
                addProperty("balancerTag", BALANCER_TAG)
            }
        )

        val outboundJsons = mapOf(
            PathRole.PRIMARY to (outbounds.objects().firstOrNull { it.tagOrNull() == PRIMARY_TAG }?.toString() ?: return null),
            PathRole.SECONDARY_B to (outbounds.objects().firstOrNull { it.tagOrNull() == SECONDARY_B_TAG }?.toString() ?: return null),
            PathRole.SECONDARY_C to (outbounds.objects().firstOrNull { it.tagOrNull() == SECONDARY_C_TAG }?.toString() ?: return null),
        )

        val serialized = root.toString()
        if (!isMultipathConfigValid(serialized)) return null
        return Injection(configJson = serialized, outboundJsons = outboundJsons)
    }

    /**
     * Réécrit uniquement le sélecteur du balancer, sans reconstruire la
     * configuration. Utilisé pour appliquer une bascule sans relancer les
     * générateurs d'outbounds.
     */
    fun withSelector(configJson: String, selector: List<String>): String? {
        val root = parseObject(configJson) ?: return null
        val routing = root.getAsJsonObject("routing") ?: return null
        val balancer = routing.getAsJsonArray("balancers")
            ?.objects()
            ?.firstOrNull { it.stringOrNull("tag") == BALANCER_TAG }
            ?: return null
        val effective = selector.filter { it in allTags }.distinct().ifEmpty { allTags }
        balancer.add("selector", JsonArray().apply { effective.forEach { add(it) } })
        val serialized = root.toString()
        return serialized.takeIf { isMultipathConfigValid(it) }
    }

    /** Vérifie la présence des trois chemins, du balancer et de l'observatoire. */
    fun isMultipathConfigValid(json: String): Boolean {
        val root = parseObject(json) ?: return false
        val outbounds = root.getAsJsonArray("outbounds") ?: return false
        val tags = outbounds.objects().mapNotNull { it.tagOrNull() }.toSet()
        if (!tags.containsAll(allTags)) return false

        val routing = root.getAsJsonObject("routing") ?: return false
        val balancer = routing.getAsJsonArray("balancers")
            ?.objects()
            ?.firstOrNull { it.stringOrNull("tag") == BALANCER_TAG }
            ?: return false
        if (balancer.stringOrNull("fallbackTag") != PRIMARY_TAG) return false
        if (balancer.getAsJsonArray("selector")?.size() ?: 0 == 0) return false

        val hasCatchAll = routing.getAsJsonArray("rules")
            ?.objects()
            ?.any { it.stringOrNull("balancerTag") == BALANCER_TAG } == true
        if (!hasCatchAll) return false

        return root.getAsJsonObject("observatory") != null
    }

    /** Réécrit toutes les références au tag principal historique. */
    private fun rewritePrimaryReferences(root: JsonObject, from: String, to: String) {
        root.getAsJsonArray("outbounds")?.objects()?.forEach { outbound ->
            if (outbound.tagOrNull() == from) outbound.addProperty("tag", to)
            outbound.getAsJsonObject("sockopt")?.let { sockopt ->
                if (sockopt.stringOrNull("dialerProxy") == from) {
                    sockopt.addProperty("dialerProxy", to)
                }
            }
        }

        val routing = root.getAsJsonObject("routing") ?: return
        routing.getAsJsonArray("rules")?.objects()?.forEach { rule ->
            if (rule.stringOrNull("outboundTag") == from) rule.addProperty("outboundTag", to)
        }
        routing.getAsJsonArray("balancers")?.objects()?.forEach { balancer ->
            if (balancer.stringOrNull("fallbackTag") == from) balancer.addProperty("fallbackTag", to)
            val selector = balancer.getAsJsonArray("selector") ?: return@forEach
            for (index in 0 until selector.size()) {
                val element = selector.get(index)
                if (element != null && element.isJsonPrimitive && element.asString == from) {
                    selector.set(index, com.google.gson.JsonPrimitive(to))
                }
            }
        }
    }

    // ── Utilitaires JSON ─────────────────────────────────────────────────────

    private fun parseObject(json: String): JsonObject? = try {
        JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
    } catch (e: Exception) {
        null
    }

    private fun JsonArray.objects(): List<JsonObject> =
        mapNotNull { element: JsonElement? -> element?.takeIf { it.isJsonObject }?.asJsonObject }

    private fun JsonArray.tagExists(tag: String): Boolean =
        objects().any { it.tagOrNull() == tag }

    private fun removeWhere(array: JsonArray, predicate: (JsonObject) -> Boolean) {
        for (index in array.size() - 1 downTo 0) {
            val element = array.get(index)
            if (element != null && element.isJsonObject && predicate(element.asJsonObject)) {
                array.remove(index)
            }
        }
    }

    private fun JsonObject.tagOrNull(): String? = stringOrNull("tag")

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
}
