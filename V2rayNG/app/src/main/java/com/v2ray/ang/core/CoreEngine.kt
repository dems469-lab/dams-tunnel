package com.v2ray.ang.core

import com.v2ray.ang.dto.OutboundTrafficStat

/**
 * Façade du moteur VPN.
 *
 * Seul contrat entre l'application et le moteur natif : les services, l'UI et le
 * laboratoire ne dépendent jamais directement de `libv2ray`. Remplacer le moteur
 * (AAR, wrapper, version de Xray-core) ne demande donc de modifier que
 * [Libv2rayEngine].
 */
interface CoreEngine {
    /**
     * Démarre le cœur avec la configuration JSON et le descripteur TUN.
     *
     * @param configContent configuration Xray complète, au format JSON.
     * @param tunFd descripteur de l'interface TUN, ou `0` pour ne pas en utiliser
     *   (mode relais hev : le cœur n'écoute que le SOCKS local).
     */
    fun start(configContent: String, tunFd: Int)

    /** Arrête le cœur. Appel natif bloquant. */
    fun stop()

    /** Vrai si le cœur se déclare démarré. */
    val isRunning: Boolean

    /**
     * Mesure la latence à travers le cœur.
     *
     * @return durée en millisecondes, ou `-1` en cas d'échec.
     */
    fun measureDelay(url: String): Long

    /**
     * Lit **et remet à zéro** les compteurs de trafic de tous les outbounds.
     *
     * @return un couple (tag, sens) par compteur non nul.
     */
    fun readOutboundTrafficStats(): List<OutboundTrafficStat>

    /** Version du wrapper et du cœur, telle que rapportée par le natif. */
    fun version(): String
}
