package com.v2ray.ang.reconfig.engine

/**
 * Validation locale des domaines et adresses saisis par l'utilisateur.
 *
 * Aucune résolution DNS n'est effectuée : la validation est purement
 * syntaxique, donc utilisable hors ligne et testable sur la JVM.
 */
internal enum class ReconfigTargetError {
    EMPTY,
    TOO_LONG,
    INVALID_CHARACTERS,
    INVALID_DOMAIN,
    IPV6_NOT_SUPPORTED,
}

internal data class ReconfigTargetValidation(
    val target: String?,
    val error: ReconfigTargetError? = null,
) {
    val isValid: Boolean get() = target != null
}

internal object ReconfigTargetValidator {
    private val ipv4Regex = Regex(
        "^(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"
    )
    private val labelRegex = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val allowedCharacters = Regex("^[A-Za-z0-9.-]+$")

    /**
     * Valide un domaine ou une adresse IPv4.
     *
     * Les schémas, ports, chemins et IPv6 sont refusés : une adresse de
     * connexion ne doit contenir qu'un hôte.
     */
    fun validate(raw: String): ReconfigTargetValidation {
        val value = raw.trim().removeSuffix(".")
        if (value.isEmpty()) {
            return ReconfigTargetValidation(null, ReconfigTargetError.EMPTY)
        }
        if (value.length > MAX_HOST_LENGTH) {
            return ReconfigTargetValidation(null, ReconfigTargetError.TOO_LONG)
        }
        // Un schéma, un chemin ou des identifiants sont refusés avant l'analyse
        // des deux-points, pour que « https://… » ne soit pas pris pour une IPv6.
        if (value.any { it == '/' || it == '@' || it == '\\' }) {
            return ReconfigTargetValidation(null, ReconfigTargetError.INVALID_CHARACTERS)
        }
        if (value.contains(':')) {
            // Une IPv6 (ou un hôte avec port) est ambiguë dans une URI VPN.
            return ReconfigTargetValidation(null, ReconfigTargetError.IPV6_NOT_SUPPORTED)
        }
        if (!allowedCharacters.matches(value)) {
            return ReconfigTargetValidation(null, ReconfigTargetError.INVALID_CHARACTERS)
        }
        if (isIpv4(value)) {
            return ReconfigTargetValidation(value)
        }
        if (!isDomain(value)) {
            return ReconfigTargetValidation(null, ReconfigTargetError.INVALID_DOMAIN)
        }
        return ReconfigTargetValidation(value)
    }

    fun isIpv4(value: String): Boolean = ipv4Regex.matches(value)

    fun isDomain(value: String): Boolean {
        if (value.isEmpty() || value.length > MAX_HOST_LENGTH) return false
        val labels = value.split('.')
        if (labels.size < 2) return false
        if (labels.any { it.isEmpty() || !labelRegex.matches(it) }) return false
        val topLevel = labels.last()
        return topLevel.length >= 2 && topLevel.any { !it.isDigit() }
    }

    /** Vrai pour un nom de domaine, une IPv4 ou une IPv6 (sans identifiants). */
    fun isAddress(value: String): Boolean {
        val cleaned = value.trim().removePrefix("[").removeSuffix("]")
        if (cleaned.isEmpty()) return false
        return isIpv4(cleaned) || isDomain(cleaned) || isIpv6(cleaned)
    }

    private fun isIpv6(value: String): Boolean =
        value.count { it == ':' } >= 2 &&
            value.all { it.isDigit() || it == ':' || it == '.' || it in 'a'..'f' || it in 'A'..'F' }

    private const val MAX_HOST_LENGTH = 253
}
