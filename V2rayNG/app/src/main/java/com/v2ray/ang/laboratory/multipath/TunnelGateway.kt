package com.v2ray.ang.laboratory.multipath

/**
 * Adaptateur de l'orchestration VPN.
 *
 * Le laboratoire ne parle jamais directement au service : il passe par cette
 * interface, implémentée par un adaptateur qui utilise les messages existants
 * du moteur (arrêt, démarrage, état vérifié). Cela permet de tester la
 * logique de bascule sans Android.
 */
interface TunnelGateway {

    /** Arrête le tunnel classique s'il tourne. Retourne true si le tunnel est arrêté. */
    suspend fun stopClassicTunnel(): Boolean

    /**
     * Démarre le tunnel laboratoire (la session doit déjà être publiée).
     * Retourne true uniquement si le moteur a confirmé un fonctionnement réel.
     */
    suspend fun startLaboratoryTunnel(): Boolean

    /** Arrête le tunnel quel qu'il soit. */
    suspend fun stopTunnel(): Boolean

    /** Dernier état connu du tunnel. */
    fun isTunnelRunning(): Boolean
}
