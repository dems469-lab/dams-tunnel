package com.v2ray.ang.reconfig.engine

import com.v2ray.ang.dto.entities.ProfileItem

internal enum class ReconfigurationStatus {
    SUCCESS,
    UNSUPPORTED_PROTOCOL,
    INVALID_ADDRESS,
    INVALID_PORT,
    INVALID_TARGET,
    ALREADY_TRANSFORMED,
    VALIDATION_FAILED,
    SERIALIZATION_FAILED,
}

/**
 * Résultat d'une reconfiguration locale.
 *
 * Le port du profil transformé est fixé à 443 : c'est la seule valeur modifiée
 * en plus de l'adresse de connexion. Le SNI, l'en-tête Host, le chemin, le
 * transport, la sécurité et les identifiants sont conservés.
 *
 * @property original profil d'origine, jamais modifié.
 * @property transformed nouveau profil, `null` en cas d'échec.
 * @property originalConfig JSON Xray de l'outbound d'origine (information, peut être vide).
 * @property transformedConfig JSON Xray de l'outbound transformé (information, peut être vide).
 */
internal data class ReconfigurationOutcome(
    val status: ReconfigurationStatus,
    val original: ProfileItem,
    val method: ReconfigMethod,
    val target: String,
    val transformed: ProfileItem? = null,
    val originalConfig: String? = null,
    val transformedConfig: String? = null,
) {
    val isSuccess: Boolean get() = status == ReconfigurationStatus.SUCCESS && transformed != null
    val originalAddress: String get() = original.server.orEmpty()
    val transformedAddress: String get() = transformed?.server.orEmpty()
    val originalPort: String get() = original.serverPort.orEmpty()
    val transformedPort: String get() = transformed?.serverPort.orEmpty()
}
