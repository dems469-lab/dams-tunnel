package com.v2ray.ang.core

import com.v2ray.ang.util.Utils

/** Dernière résolution connue d'un domaine, avec sa date. */
internal data class CachedServerAddress(
    val ips: List<String>,
    val resolvedAtMillis: Long,
)

/** Stockage persistant des résolutions, utilisé quand la résolution en direct échoue. */
internal interface ServerAddressCache {
    fun read(domain: String): CachedServerAddress?

    fun write(domain: String, ips: List<String>, resolvedAtMillis: Long)
}

/**
 * Résultat de la pré-résolution : IP fraîches, IP du cache, domaines sans aucune IP.
 * [unresolved] n'est jamais une erreur : l'appelant démarre quand même.
 */
internal data class ServerAddressResolution(
    val fresh: Map<String, List<String>>,
    val cached: Map<String, List<String>>,
    val unresolved: List<String>,
) {
    fun ips(domain: String): List<String>? = fresh[domain] ?: cached[domain]
}

/**
 * Pré-résout les domaines des serveurs proxy.
 *
 * - une adresse déjà littérale (IP) n'est jamais résolue ;
 * - la résolution passe par [resolveDirect], qui doit interroger le réseau sous-jacent
 *   (jamais le DNS du tunnel) ;
 * - chaque résolution réussie est écrite dans [cache] avec sa date ;
 * - en cas d'échec, la dernière valeur du cache est utilisée ;
 * - sans cache, le domaine est seulement signalé dans [ServerAddressResolution.unresolved].
 */
internal class ServerAddressResolver(
    private val resolveDirect: (String) -> List<String>?,
    private val cache: ServerAddressCache,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun resolve(domains: Collection<String>, timeoutMillis: Long): ServerAddressResolution {
        val candidates = domains.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !Utils.isPureIpAddress(it) }
            .distinct()
            .toList()
        if (candidates.isEmpty()) {
            return ServerAddressResolution(emptyMap(), emptyMap(), emptyList())
        }

        val fresh = OutboundDomainResolver.resolve(candidates, timeoutMillis) { domain ->
            resolveDirect(domain)
                ?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
        }
        val resolvedAt = now()
        fresh.forEach { (domain, ips) -> cache.write(domain, ips, resolvedAt) }

        val cached = LinkedHashMap<String, List<String>>()
        val unresolved = ArrayList<String>()
        for (domain in candidates) {
            if (fresh.containsKey(domain)) continue
            val entry = cache.read(domain)
            if (entry != null && entry.ips.isNotEmpty()) {
                cached[domain] = entry.ips
            } else {
                unresolved.add(domain)
            }
        }
        return ServerAddressResolution(fresh, cached, unresolved)
    }
}

/** Réseau candidat pour une résolution hors tunnel. */
internal data class NetworkCandidate(
    val id: Long,
    val isVpn: Boolean,
    val hasInternet: Boolean,
    val isValidated: Boolean,
)

/** Choisit un réseau sous-jacent : jamais le VPN, de préférence un réseau validé. */
internal fun selectUnderlyingNetwork(candidates: List<NetworkCandidate>): NetworkCandidate? =
    candidates.asSequence()
        .filter { !it.isVpn && it.hasInternet }
        .sortedWith(compareByDescending<NetworkCandidate> { it.isValidated }.thenBy { it.id })
        .firstOrNull()

/** Trie les adresses selon la préférence IPv6, sans jamais en perdre. */
internal fun sortResolvedAddresses(addresses: List<String>, preferIpv6: Boolean): List<String> {
    val comparator = if (preferIpv6) {
        compareByDescending<String> { it.contains(':') }
    } else {
        compareBy<String> { it.contains(':') }
    }
    return addresses.sortedWith(comparator)
}
