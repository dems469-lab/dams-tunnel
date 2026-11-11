package com.v2ray.ang.reconfig.data

import com.v2ray.ang.reconfig.engine.ReconfigMethod
import java.security.MessageDigest

/**
 * État du dernier ping TCP.
 *
 * L'application n'utilise pas ICMP (réservé au système) : le ping est une
 * connexion TCP chronométrée, ce que l'interface indique explicitement.
 */
internal enum class ReconfigPingState {
    PENDING,
    RUNNING,
    SUCCESS,
    TIMEOUT,
    DNS_ERROR,
    HOST_UNREACHABLE,
    CONNECTION_ERROR,
    ERROR,
}

/**
 * État du dernier test réel de tunnel.
 *
 * [FUNCTIONAL] n'est jamais produit par le simple démarrage du cœur : il exige
 * une requête HTTP réussie à travers le tunnel temporaire.
 */
internal enum class ReconfigTestState {
    PENDING,
    RUNNING,
    FUNCTIONAL,
    UNREACHABLE,
    INVALID_CONFIG,
    CORE_ERROR,
    CANCELLED,
}

/**
 * Serveur préparé par le module de reconfiguration.
 *
 * Les deux représentations textuelles sont conservées :
 * - [transformedConfig] : JSON Xray complet de l'outbound (`vnext` pour
 *   VLESS/VMess, `servers` pour Trojan), jamais le lien brut ;
 * - [transformedProfileJson] : profil complet, utilisé pour le transfert vers
 *   Configs afin de ne perdre aucun paramètre.
 *
 * @property configHash empreinte du résultat, utilisée pour refuser les doublons.
 */
internal data class ReconfiguredServer(
    val id: Long = 0L,
    val configHash: String = "",
    val originalConfigId: String = "",
    val originalConfig: String = "",
    val transformedConfig: String = "",
    val transformedProfileJson: String = "",
    val name: String = "",
    val protocol: String = "",
    val originalAddress: String = "",
    val transformedAddress: String = "",
    val originalPort: String = "",
    val transformedPort: String = "",
    val reconfigurationMethod: String = ReconfigMethod.MAXIT_MOBILE.storageKey,
    val target: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val pingMs: Long = -1L,
    val pingState: ReconfigPingState = ReconfigPingState.PENDING,
    val testState: ReconfigTestState = ReconfigTestState.PENDING,
    val lastPingAt: Long = 0L,
    val lastTestAt: Long = 0L,
    val errorMessage: String? = null,
    val isValid: Boolean = true,
    val isFunctional: Boolean = false,
) {
    val method: ReconfigMethod get() = ReconfigMethod.fromStorageKey(reconfigurationMethod) ?: ReconfigMethod.CUSTOM
    val hasLatency: Boolean get() = pingState == ReconfigPingState.SUCCESS && pingMs >= 0L
}

internal object ReconfigurationFingerprint {
    /**
     * Empreinte SHA-256 d'une chaîne, sans jamais exposer son contenu.
     */
    fun of(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        digest.forEach { byte -> builder.append("%02x".format(byte)) }
        return builder.toString()
    }
}
