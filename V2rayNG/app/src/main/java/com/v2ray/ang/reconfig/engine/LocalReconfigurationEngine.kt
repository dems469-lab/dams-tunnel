package com.v2ray.ang.reconfig.engine

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

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
 * @property original profil d'origine, jamais modifié.
 * @property transformed nouveau profil, `null` en cas d'échec.
 * @property originalConfig URI d'origine (information, peut être vide).
 * @property transformedConfig URI du résultat (information, peut être vide).
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

/**
 * Moteur de reconfiguration local.
 *
 * Règles appliquées :
 * - seuls VLESS, VMess et Trojan sont reconfigurables ;
 * - seule l'adresse de connexion (`server`) est modifiée ;
 * - SNI, en-tête Host, chemin, transport, sécurité, port et identifiants sont
 *   conservés tels quels (une modification de l'adresse ne change jamais le
 *   SNI/Host, qui restent les seuls garants de l'identité TLS du serveur) ;
 * - le port n'est jamais forcé : il dépend du point d'accès cible ;
 * - un préfixe n'est appliqué qu'à un nom de domaine et une seule fois ;
 * - le profil d'origine n'est jamais modifié : le résultat est une copie ;
 * - le résultat est validé avant d'être annoncé comme réussi.
 *
 * @param codec sérialiseur utilisé pour vérifier que le résultat reste
 * décodable. `null` désactive cette vérification (tests unitaires JVM).
 */
internal class LocalReconfigurationEngine(
    private val codec: ConfigurationCodec? = AppConfigurationCodec,
) {
    fun reconfigure(
        profile: ProfileItem,
        method: ReconfigMethod,
        targetInput: String,
    ): ReconfigurationOutcome {
        if (profile.configType !in SUPPORTED_TYPES) {
            return failure(ReconfigurationStatus.UNSUPPORTED_PROTOCOL, profile, method, targetInput)
        }

        val targetValidation = ReconfigTargetValidator.validate(targetInput)
        val target = targetValidation.target
            ?: return failure(ReconfigurationStatus.INVALID_TARGET, profile, method, targetInput)

        val address = profile.server?.trim().orEmpty()
        if (address.isEmpty() || address.any { it.isWhitespace() } || !ReconfigTargetValidator.isAddress(address)) {
            return failure(ReconfigurationStatus.INVALID_ADDRESS, profile, method, target)
        }

        val port = profile.serverPort?.trim()?.toIntOrNull()
        if (port == null || port !in 1..65535) {
            return failure(ReconfigurationStatus.INVALID_PORT, profile, method, target)
        }

        val newAddress = when {
            method.usesPrefix -> {
                // Un préfixe n'a de sens qu'avec un nom de domaine.
                if (!ReconfigTargetValidator.isDomain(address)) {
                    return failure(ReconfigurationStatus.INVALID_ADDRESS, profile, method, target)
                }
                if (address.equals(target, ignoreCase = true) ||
                    address.startsWith("$target.", ignoreCase = true)
                ) {
                    return failure(ReconfigurationStatus.ALREADY_TRANSFORMED, profile, method, target)
                }
                "$target.$address"
            }

            else -> {
                if (address.equals(target, ignoreCase = true)) {
                    return failure(ReconfigurationStatus.ALREADY_TRANSFORMED, profile, method, target)
                }
                target
            }
        }

        if (!ReconfigTargetValidator.isAddress(newAddress)) {
            return failure(ReconfigurationStatus.VALIDATION_FAILED, profile, method, target)
        }

        // `copy` conserve tous les autres champs, y compris ceux que le module
        // ne connaît pas : aucun paramètre n'est supprimé par construction.
        val transformed = profile.copy(server = newAddress)

        if (!preservesInvariants(profile, transformed)) {
            return failure(ReconfigurationStatus.VALIDATION_FAILED, profile, method, target)
        }

        val originalConfig = codec?.encode(profile)
        val transformedConfig = codec?.encode(transformed)
        if (codec != null) {
            val decoded = transformedConfig?.let(codec::decode)
                ?: return failure(ReconfigurationStatus.SERIALIZATION_FAILED, profile, method, target)
            if (!roundTripMatches(transformed, decoded)) {
                return failure(ReconfigurationStatus.VALIDATION_FAILED, profile, method, target)
            }
        }

        return ReconfigurationOutcome(
            status = ReconfigurationStatus.SUCCESS,
            original = profile,
            method = method,
            target = target,
            transformed = transformed,
            originalConfig = originalConfig,
            transformedConfig = transformedConfig,
        )
    }

    private fun failure(
        status: ReconfigurationStatus,
        profile: ProfileItem,
        method: ReconfigMethod,
        target: String,
    ) = ReconfigurationOutcome(
        status = status,
        original = profile,
        method = method,
        target = target,
    )

    /**
     * Vérifie qu'aucune donnée sensible ou indispensable n'a bougé.
     */
    private fun preservesInvariants(original: ProfileItem, transformed: ProfileItem): Boolean =
        original.configType == transformed.configType &&
            original.serverPort == transformed.serverPort &&
            original.password == transformed.password &&
            original.method == transformed.method &&
            original.flow == transformed.flow &&
            original.network == transformed.network &&
            original.headerType == transformed.headerType &&
            original.host == transformed.host &&
            original.path == transformed.path &&
            original.security == transformed.security &&
            original.sni == transformed.sni &&
            original.alpn == transformed.alpn &&
            original.fingerPrint == transformed.fingerPrint &&
            original.insecure == transformed.insecure &&
            original.publicKey == transformed.publicKey &&
            original.shortId == transformed.shortId &&
            original.serviceName == transformed.serviceName &&
            original.authority == transformed.authority &&
            original.mode == transformed.mode &&
            original.finalMask == transformed.finalMask &&
            original.xhttpExtra == transformed.xhttpExtra

    /**
     * Vérifie que l'URI produite se relit avec les mêmes données critiques.
     */
    private fun roundTripMatches(expected: ProfileItem, decoded: ProfileItem): Boolean =
        expected.configType == decoded.configType &&
            expected.server.orEmpty().equals(decoded.server.orEmpty(), ignoreCase = true) &&
            expected.serverPort.orEmpty() == decoded.serverPort.orEmpty() &&
            expected.password.orEmpty() == decoded.password.orEmpty() &&
            expected.network.orEmpty().equals(decoded.network.orEmpty(), ignoreCase = true) &&
            expected.security.orEmpty().equals(decoded.security.orEmpty(), ignoreCase = true)

    private companion object {
        val SUPPORTED_TYPES = setOf(EConfigType.VLESS, EConfigType.VMESS, EConfigType.TROJAN)
    }
}
