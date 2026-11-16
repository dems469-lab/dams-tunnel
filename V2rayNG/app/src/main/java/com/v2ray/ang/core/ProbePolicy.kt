package com.v2ray.ang.core

/**
 * Décisions pures des sondes de connectivité.
 *
 * Une sonde ne prouve jamais, à elle seule, que le tunnel est mort : une URL
 * peut être bloquée par le serveur, un résolveur peut traîner, le téléphone
 * peut n'avoir aucun réseau. Ce fichier rassemble les règles qui décident de
 * l'état réel à partir de plusieurs tentatives et de trois signaux
 * indépendants : le cœur, le relais TUN et l'accès Internet sous-jacent.
 *
 * Aucune dépendance Android ou native : tout est exerçable sur JVM.
 */

/** Fournisseur d'une destination : sert à exiger plusieurs avis indépendants. */
internal enum class ProbeProvider {
    GOOGLE,
    CLOUDFLARE,
    CANONICAL,
    OTHER,
}

/** Résultat d'une destination : seul [OK] prouve que la requête a abouti. */
internal enum class ProbeKind {
    OK,
    TIMEOUT,
    ERROR,
}

/** État déduit d'une passe de sondes. */
internal enum class TunnelVerdict {
    /** Au moins une destination répond vite : le tunnel transporte. */
    UP,

    /** Le tunnel répond, mais lentement : joignable, qualité dégradée. */
    SLOW,

    /** Toutes les destinations indépendantes échouent alors qu'Internet fonctionne. */
    DOWN,

    /** Preuve insuffisante (une seule destination, sonde ignorée) : ne rien casser. */
    SUSPECT,

    /** Aucun accès Internet sous-jacent : ce n'est pas une panne du tunnel. */
    NO_INTERNET,

    /** Le moteur ne tourne pas. */
    CORE_DOWN,

    /** Le relais TUN ne transporte plus les paquets. */
    RELAY_DOWN,

    /** Une autre sonde était en cours : cette passe ne prouve rien. */
    SKIPPED,
}

internal data class ProbeEndpoint(
    val url: String,
    val provider: ProbeProvider,
)

internal data class ProbeAttempt(
    val url: String,
    val provider: ProbeProvider,
    val kind: ProbeKind,
    /** Latence mesurée si [kind] vaut [ProbeKind.OK], durée écoulée sinon. */
    val elapsedMillis: Long,
)

/**
 * Photographie d'une passe de sondes.
 *
 * [latencyMillis] vaut -1 quand aucune destination n'a répondu : les appelants
 * peuvent donc conserver la convention historique « < 0 = pas de mesure » sans
 * confondre un échec prouvé avec une sonde ignorée.
 */
internal data class ProbeReport(
    val verdict: TunnelVerdict,
    val latencyMillis: Long,
    val attempts: List<ProbeAttempt> = emptyList(),
    val coreRunning: Boolean = false,
    val relayRunning: Boolean = false,
    val internetValidated: Boolean = true,
) {
    /** Le tunnel a répondu : un succès lent reste un succès. */
    val reachable: Boolean
        get() = verdict == TunnelVerdict.UP || verdict == TunnelVerdict.SLOW

    /** Panne prouvée par plusieurs fournisseurs alors qu'Internet fonctionne. */
    val confirmedDown: Boolean
        get() = verdict == TunnelVerdict.DOWN

    /** Aucune mesure exploitable : ne doit jamais compter comme un échec. */
    val skipped: Boolean
        get() = verdict == TunnelVerdict.SKIPPED

    val providersAttempted: Int
        get() = attempts.map { it.provider }.distinct().size

    val providersReachable: Int
        get() = attempts
            .asSequence()
            .filter { it.kind == ProbeKind.OK }
            .map { it.provider }
            .distinct()
            .count()
}

internal object ProbePolicy {

    /**
     * Au-delà de ce seuil, un succès reste une preuve de vie mais sa latence ne
     * doit pas entrer dans le classement de bascule : elle reflète la congestion
     * du moment, pas la qualité du serveur.
     */
    const val SLOW_PROBE_MILLIS = 2_500L

    /** Part minimale réservée à chaque destination quand le budget le permet. */
    const val MIN_PER_URL_BUDGET_MILLIS = 1_500L

    /**
     * Nombre de fournisseurs distincts dont l'échec est nécessaire pour
     * conclure à une panne : une seule destination ne décide jamais de l'état.
     */
    const val MIN_PROVIDERS_FOR_DOWN = 2

    private val GOOGLE_HOSTS = listOf("google", "gstatic", "googleapis", "googlevideo")
    private val CLOUDFLARE_HOSTS = listOf("cloudflare", "cloudflare-dns")
    private val CANONICAL_HOSTS = listOf("ubuntu", "canonical")

    /** Classe une destination par fournisseur, à partir de son hôte. */
    fun providerOf(url: String): ProbeProvider {
        val host = hostOf(url).lowercase()
        return when {
            GOOGLE_HOSTS.any { host.contains(it) } -> ProbeProvider.GOOGLE
            CLOUDFLARE_HOSTS.any { host.contains(it) } -> ProbeProvider.CLOUDFLARE
            CANONICAL_HOSTS.any { host.contains(it) } -> ProbeProvider.CANONICAL
            else -> ProbeProvider.OTHER
        }
    }

    /**
     * Construit la liste d'essai, dans l'ordre fourni, en supprimant les
     * doublons d'hôte : la même destination ne peut pas compter deux fois.
     */
    fun endpoints(urls: List<String>): List<ProbeEndpoint> =
        urls.asSequence()
            .filter { it.isNotBlank() }
            .distinctBy { hostOf(it) }
            .map { ProbeEndpoint(it, providerOf(it)) }
            .toList()

    /**
     * Répartit un budget total entre [count] destinations.
     *
     * L'ancien calcul `total / 2` réservait la moitié du temps à la première
     * URL : avec trois destinations, la dernière n'était jamais essayée, donc
     * un serveur bloquant Google ne pouvait jamais être déclaré connecté. Ici
     * chaque destination reçoit au moins `total / count` (plafonné par
     * [minPerUrl]) et le reliquat est distribué milliseconde par milliseconde.
     *
     * @return un budget par destination, dans l'ordre ; vide si le budget total
     *   ne permet aucune tentative.
     */
    fun planBudgets(
        totalMillis: Long,
        count: Int,
        minPerUrl: Long = MIN_PER_URL_BUDGET_MILLIS,
    ): List<Long> {
        if (totalMillis <= 0L || count <= 0) return emptyList()
        val share = totalMillis / count
        if (share <= 0L) return emptyList()
        val base = minOf(minPerUrl.coerceAtLeast(1L), share)
        val budgets = MutableList(count) { base }
        var leftover = totalMillis - base * count
        var index = 0
        while (leftover > 0L) {
            budgets[index % count] += 1L
            index++
            leftover--
        }
        return budgets
    }

    /**
     * Déduit l'état du tunnel.
     *
     * Ordre volontaire : le cœur, puis le relais, puis les réponses. Un relais
     * mort rend le tunnel inutilisable même si la sonde (qui ne traverse pas le
     * TUN) répond.
     */
    fun evaluate(
        coreRunning: Boolean,
        relayRunning: Boolean,
        internetValidated: Boolean,
        attempts: List<ProbeAttempt>,
        skipped: Boolean = false,
        slowMillis: Long = SLOW_PROBE_MILLIS,
        minProvidersForDown: Int = MIN_PROVIDERS_FOR_DOWN,
    ): ProbeReport {
        fun report(verdict: TunnelVerdict, latency: Long) = ProbeReport(
            verdict = verdict,
            latencyMillis = latency,
            attempts = attempts,
            coreRunning = coreRunning,
            relayRunning = relayRunning,
            internetValidated = internetValidated,
        )

        if (skipped) return report(TunnelVerdict.SKIPPED, -1L)
        if (!coreRunning) return report(TunnelVerdict.CORE_DOWN, -1L)
        if (!relayRunning) return report(TunnelVerdict.RELAY_DOWN, -1L)

        val successes = attempts.filter { it.kind == ProbeKind.OK }
        if (successes.isNotEmpty()) {
            val best = successes.minOf { it.elapsedMillis }
            return report(
                if (best > slowMillis) TunnelVerdict.SLOW else TunnelVerdict.UP,
                best,
            )
        }

        // Aucune tentative : rien n'a été prouvé, ni dans un sens ni dans l'autre.
        if (attempts.isEmpty()) return report(TunnelVerdict.SUSPECT, -1L)
        // Sans réseau porteur, la sonde ne peut pas traverser quoi que ce soit.
        if (!internetValidated) return report(TunnelVerdict.NO_INTERNET, -1L)

        val providers = attempts.map { it.provider }.distinct().size
        return if (providers >= minProvidersForDown.coerceAtLeast(1)) {
            report(TunnelVerdict.DOWN, -1L)
        } else {
            // Une seule destination en échec ne suffit jamais à condamner le tunnel.
            report(TunnelVerdict.SUSPECT, -1L)
        }
    }

    /** Hôte normalisé d'une URL, utilisé pour le dédoublonnage et le classement. */
    fun hostOf(url: String): String {
        val raw = url.trim()
        val withoutScheme = raw.substringAfter("://", raw)
        val authority = withoutScheme.substringBefore('/').substringBefore('?')
        val host = authority.substringAfter('@').substringBefore(':')
        return host.ifBlank { raw }
    }
}
