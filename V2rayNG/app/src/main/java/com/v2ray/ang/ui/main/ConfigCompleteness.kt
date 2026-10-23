package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.util.UUID

/**
 * Structural assessment only. It never tests a server and never exposes a secret.
 */
internal enum class ConfigCompletenessStatus {
    COMPLETE,
    PARTIAL,
    INVALID,
    UNVERIFIED,
}

internal data class ConfigCompleteness(
    val protocol: String,
    val status: ConfigCompletenessStatus,
    val score: Int,
    val missingFields: List<String>,
)

internal fun assessConfigCompleteness(profile: ProfileItem): ConfigCompleteness {
    val protocol = profile.configType.protocolScheme.uppercase()
    val missing = mutableListOf<String>()
    var score = 0
    var requiredValid = true

    fun required(condition: Boolean, label: String, points: Int) {
        if (condition) score += points else {
            requiredValid = false
            missing += label
        }
    }

    fun optional(condition: Boolean, label: String, points: Int) {
        if (condition) score += points else missing += label
    }

    val serverValid = !profile.server.isNullOrBlank() && !profile.server!!.any(Char::isWhitespace)
    val portValid = profile.serverPort?.toIntOrNull()?.let { it in 1..65535 } == true
    val uuidValid = profile.password?.let(::isUuid) == true

    when (profile.configType) {
        EConfigType.VMESS, EConfigType.VLESS -> {
            required(serverValid, "serveur", 35)
            required(portValid, "port", 25)
            required(uuidValid, "identifiant", 25)
            val network = profile.network.orEmpty().lowercase()
            when (network) {
                "ws", "websocket" -> {
                    optional(!profile.path.isNullOrBlank(), "chemin", 7)
                    optional(!profile.host.isNullOrBlank(), "Host", 8)
                }
                "grpc" -> optional(!profile.serviceName.isNullOrBlank(), "service gRPC", 15)
                "xhttp", "splithttp" -> optional(!profile.path.isNullOrBlank(), "chemin", 7)
            }
            if (profile.security.equals("tls", true) || profile.security.equals("reality", true)) {
                optional(!profile.sni.isNullOrBlank(), "SNI", 10)
            }
        }
        EConfigType.TROJAN -> {
            required(serverValid, "serveur", 35)
            required(portValid, "port", 25)
            // Trojan passwords are opaque credentials: presence is checked, never displayed or logged.
            required(!profile.password.isNullOrBlank(), "mot de passe", 25)
            val network = profile.network.orEmpty().lowercase()
            if (network == "ws" || network == "websocket") {
                optional(!profile.path.isNullOrBlank(), "chemin", 7)
                optional(!profile.host.isNullOrBlank(), "Host", 8)
            }
            if (profile.security.equals("tls", true)) optional(!profile.sni.isNullOrBlank(), "SNI", 10)
        }
        EConfigType.SHADOWSOCKS -> {
            required(serverValid, "serveur", 35)
            required(portValid, "port", 25)
            required(!profile.password.isNullOrBlank(), "mot de passe", 25)
            required(!profile.method.isNullOrBlank(), "méthode", 15)
        }
        EConfigType.SOCKS, EConfigType.HTTP -> {
            required(serverValid, "serveur", 45)
            required(portValid, "port", 35)
            optional(profile.username.isNullOrBlank() || !profile.password.isNullOrBlank(), "identifiants", 20)
        }
        else -> return ConfigCompleteness(protocol, ConfigCompletenessStatus.UNVERIFIED, 0, emptyList())
    }

    val status = when {
        !requiredValid -> ConfigCompletenessStatus.INVALID
        missing.isEmpty() -> ConfigCompletenessStatus.COMPLETE
        else -> ConfigCompletenessStatus.PARTIAL
    }
    return ConfigCompleteness(protocol, status, score.coerceIn(0, 100), missing)
}

private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

internal data class RankedConfig(
    val guid: String,
    val profile: ProfileItem,
    val completeness: ConfigCompleteness,
    val delayMillis: Long,
)

internal fun rankConfigs(items: List<RankedConfig>): List<RankedConfig> = items.sortedWith(
    compareByDescending<RankedConfig> { statusRank(it.completeness.status) }
        .thenByDescending { it.completeness.score }
        .thenBy { if (it.delayMillis < 0) Long.MAX_VALUE else it.delayMillis }
        .thenByDescending { it.profile.addedTime }
)

private fun statusRank(status: ConfigCompletenessStatus): Int = when (status) {
    ConfigCompletenessStatus.COMPLETE -> 3
    ConfigCompletenessStatus.PARTIAL -> 2
    ConfigCompletenessStatus.UNVERIFIED -> 1
    ConfigCompletenessStatus.INVALID -> 0
}
