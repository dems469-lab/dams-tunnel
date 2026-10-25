package com.v2ray.ang.publicconfigs

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.util.UUID

internal enum class PublicProtocol(val configTypes: Set<EConfigType>) {
    VLESS(setOf(EConfigType.VLESS)),
    VMESS(setOf(EConfigType.VMESS)),
    TROJAN(setOf(EConfigType.TROJAN)),
    SHADOWSOCKS(setOf(EConfigType.SHADOWSOCKS)),
    SOCKS(setOf(EConfigType.SOCKS)),
    HTTP(setOf(EConfigType.HTTP)),
    HYSTERIA(setOf(EConfigType.HYSTERIA)),
    HYSTERIA2(setOf(EConfigType.HYSTERIA2)),
    WIREGUARD(setOf(EConfigType.WIREGUARD)),
    CUSTOM(setOf(EConfigType.CUSTOM));

    companion object {
        fun from(configType: EConfigType): PublicProtocol? = entries.firstOrNull { configType in it.configTypes }
    }
}

internal enum class PublicCompleteness { COMPLETE, PARTIAL, INVALID, UNVERIFIED }
internal enum class PublicAvailability { RESPONDED, NO_RESPONSE, UNKNOWN }
internal enum class PublicVerification { TESTED, UNTESTED }
internal enum class PublicSort { COMPLETENESS, LATENCY, SOURCE_UPDATED }

internal data class PublicFieldAssessment(
    val completeness: PublicCompleteness,
    val score: Int,
    val missingFieldKeys: List<String>,
)

internal data class PublicServerInput(
    val guid: String,
    val profile: ProfileItem,
    val sourceName: String,
    val sourceUpdatedAt: Long,
    val delayMillis: Long,
    val sourceId: String = profile.subscriptionId,
)

/** UI-safe fields only. Credentials and identifiers are never copied into this model. */
internal data class PublicServerDetails(
    val address: String,
    val port: String,
    val transport: String,
    val security: String,
    val path: String,
    val serverName: String,
    val headerHost: String,
    val serviceName: String,
    val authenticationPresent: Boolean,
    val publicKeyPresent: Boolean,
)

internal data class PublicServerVariant(
    val guid: String,
    val name: String,
    val sourceId: String,
    val sourceName: String,
    val protocol: PublicProtocol,
    val assessment: PublicFieldAssessment,
    val availability: PublicAvailability,
    val verification: PublicVerification,
    val delayMillis: Long?,
    val sourceUpdatedAt: Long,
    val details: PublicServerDetails,
    val searchableText: String,
)

internal data class PublicServerGroup(
    /** Stable domain id from one of the retained profiles, never a visible list position. */
    val id: String,
    val variants: List<PublicServerVariant>,
) {
    val protocol: PublicProtocol get() = variants.first().protocol
    val assessment: PublicFieldAssessment get() = variants.first().assessment
    val availability: PublicAvailability get() = when {
        variants.any { it.availability == PublicAvailability.RESPONDED } -> PublicAvailability.RESPONDED
        variants.all { it.availability == PublicAvailability.NO_RESPONSE } -> PublicAvailability.NO_RESPONSE
        else -> PublicAvailability.UNKNOWN
    }
    val bestMeasuredLatency: Long? get() = variants.mapNotNull { it.delayMillis }.minOrNull()
    val sourceUpdatedAt: Long get() = variants.maxOfOrNull { it.sourceUpdatedAt } ?: -1L
}

internal data class PublicServerFilters(
    val query: String = "",
    val protocol: PublicProtocol? = null,
    val completeness: PublicCompleteness? = null,
    val availability: PublicAvailability? = null,
    val verification: PublicVerification? = null,
    val sort: PublicSort = PublicSort.COMPLETENESS,
)

internal data class PublicServerCatalog(
    val groups: List<PublicServerGroup>,
    val protocolCounts: Map<PublicProtocol, Int>,
    val totalProfiles: Int,
)

internal fun assessPublicProfile(profile: ProfileItem): PublicFieldAssessment {
    val type = profile.configType
    if (PublicProtocol.from(type) == null || type in setOf(EConfigType.POLICYGROUP, EConfigType.PROXYCHAIN)) {
        return PublicFieldAssessment(PublicCompleteness.UNVERIFIED, 0, emptyList())
    }

    val missing = mutableListOf<String>()
    var score = 0
    var invalidRequired = false

    fun required(present: Boolean, valid: Boolean = present, key: String, points: Int) {
        if (!present) {
            missing += key
            invalidRequired = true
        } else if (!valid) {
            missing += key
            invalidRequired = true
        } else score += points
    }

    fun optional(applicable: Boolean, present: Boolean, key: String, points: Int) {
        if (!applicable) return
        if (present) score += points else missing += key
    }

    val server = profile.server.orEmpty()
    val validServer = server.isNotBlank() && server.none(Char::isWhitespace)
    val portValid = profile.serverPort?.toIntOrNull()?.let { it in 1..65535 } == true
    val transport = profile.network.orEmpty().lowercase().ifBlank { "tcp" }
    val supportedTransport = transport in setOf("tcp", "raw", "ws", "websocket", "grpc", "kcp", "quic", "httpupgrade", "xhttp", "splithttp")
    if (!supportedTransport) return PublicFieldAssessment(PublicCompleteness.UNVERIFIED, 0, emptyList())

    when (type) {
        EConfigType.VLESS, EConfigType.VMESS -> {
            required(validServer, key = "address", points = 30)
            required(profile.serverPort != null, portValid, "port", 20)
            val userIdValid = profile.password?.let { runCatching { UUID.fromString(it) }.isSuccess } == true
            required(profile.password != null, userIdValid, "user_id", 25)
            optional(transport == "ws" || transport == "websocket", !profile.path.isNullOrBlank(), "path", 8)
            optional(transport == "ws" || transport == "websocket", !profile.host.isNullOrBlank(), "host", 7)
            optional(transport == "grpc", !profile.serviceName.isNullOrBlank(), "service_name", 8)
            val tlsOrReality = profile.security.equals("tls", true) || profile.security.equals("reality", true)
            optional(tlsOrReality, !profile.sni.isNullOrBlank(), "sni", 5)
            if (profile.security.equals("reality", true)) {
                required(profile.publicKey != null, !profile.publicKey.isNullOrBlank(), "reality_public_key", 5)
            }
        }
        EConfigType.TROJAN -> {
            required(validServer, key = "address", points = 35)
            required(profile.serverPort != null, portValid, "port", 25)
            required(profile.password != null, !profile.password.isNullOrBlank(), "credential", 30)
            optional(transport == "ws" || transport == "websocket", !profile.path.isNullOrBlank(), "path", 5)
            optional(transport == "ws" || transport == "websocket", !profile.host.isNullOrBlank(), "host", 5)
            optional(profile.security.equals("tls", true), !profile.sni.isNullOrBlank(), "sni", 5)
        }
        EConfigType.SHADOWSOCKS -> {
            required(validServer, key = "address", points = 35)
            required(profile.serverPort != null, portValid, "port", 25)
            required(profile.password != null, !profile.password.isNullOrBlank(), "credential", 25)
            required(profile.method != null, !profile.method.isNullOrBlank(), "method", 15)
        }
        EConfigType.SOCKS, EConfigType.HTTP -> {
            required(validServer, key = "address", points = 50)
            required(profile.serverPort != null, portValid, "port", 40)
            val credentialsValid = profile.username.isNullOrBlank() == profile.password.isNullOrBlank()
            required(credentialsValid, credentialsValid, "credentials", 10)
        }
        EConfigType.HYSTERIA, EConfigType.HYSTERIA2 -> {
            required(validServer, key = "address", points = 50)
            required(profile.serverPort != null, portValid, "port", 40)
            optional(true, !profile.password.isNullOrBlank(), "authentication", 10)
        }
        EConfigType.WIREGUARD -> {
            required(validServer, key = "address", points = 25)
            required(profile.serverPort != null, portValid, "port", 15)
            required(profile.secretKey != null, !profile.secretKey.isNullOrBlank(), "private_key", 30)
            required(profile.publicKey != null, !profile.publicKey.isNullOrBlank(), "public_key", 20)
            required(profile.localAddress != null, !profile.localAddress.isNullOrBlank(), "local_address", 10)
        }
        EConfigType.CUSTOM -> return PublicFieldAssessment(PublicCompleteness.UNVERIFIED, 0, emptyList())
        else -> return PublicFieldAssessment(PublicCompleteness.UNVERIFIED, 0, emptyList())
    }

    val status = when {
        invalidRequired -> PublicCompleteness.INVALID
        missing.isNotEmpty() -> PublicCompleteness.PARTIAL
        else -> PublicCompleteness.COMPLETE
    }
    return PublicFieldAssessment(status, score.coerceIn(0, 100), missing)
}

internal fun buildPublicServerCatalog(
    entries: List<PublicServerInput>,
    filters: PublicServerFilters,
): PublicServerCatalog {
    val prepared = entries.mapNotNull { entry ->
        val protocol = PublicProtocol.from(entry.profile.configType) ?: return@mapNotNull null
        if (entry.profile.configType in setOf(EConfigType.POLICYGROUP, EConfigType.PROXYCHAIN)) return@mapNotNull null
        val assessment = assessPublicProfile(entry.profile)
        val availability = when {
            entry.delayMillis > 0 -> PublicAvailability.RESPONDED
            entry.delayMillis < 0 -> PublicAvailability.NO_RESPONSE
            else -> PublicAvailability.UNKNOWN
        }
        val verification = if (entry.delayMillis == 0L) PublicVerification.UNTESTED else PublicVerification.TESTED
        val searchableText = listOf(
            entry.profile.remarks,
            entry.profile.description.orEmpty(),
            entry.sourceName,
            protocol.name,
            entry.profile.server.orEmpty(),
        ).joinToString(" ")
        PublicServerVariant(
            guid = entry.guid,
            name = redactPublicLabel(entry.profile.remarks.ifBlank { entry.profile.server.orEmpty() }),
            sourceId = entry.sourceId,
            sourceName = entry.sourceName,
            protocol = protocol,
            assessment = assessment,
            availability = availability,
            verification = verification,
            delayMillis = entry.delayMillis.takeIf { it > 0 },
            sourceUpdatedAt = entry.sourceUpdatedAt,
            details = PublicServerDetails(
                address = entry.profile.server.orEmpty(),
                port = entry.profile.serverPort.orEmpty(),
                transport = entry.profile.network.orEmpty().ifBlank { "TCP" },
                security = entry.profile.security.orEmpty(),
                path = entry.profile.path.orEmpty(),
                serverName = entry.profile.sni.orEmpty(),
                headerHost = entry.profile.host.orEmpty(),
                serviceName = entry.profile.serviceName.orEmpty(),
                authenticationPresent = !entry.profile.password.isNullOrBlank() || !entry.profile.secretKey.isNullOrBlank(),
                publicKeyPresent = !entry.profile.publicKey.isNullOrBlank(),
            ),
            searchableText = searchableText,
        )
    }.filter { variant ->
        (filters.protocol == null || variant.protocol == filters.protocol) &&
            (filters.completeness == null || variant.assessment.completeness == filters.completeness) &&
            (filters.availability == null || variant.availability == filters.availability) &&
            (filters.verification == null || variant.verification == filters.verification) &&
            (filters.query.isBlank() || variant.searchableText.contains(filters.query.trim(), ignoreCase = true))
    }

    val protocolCounts = prepared.groupingBy(PublicServerVariant::protocol).eachCount()
    val profileByGuid = entries.associate { it.guid to it.profile }
    val groups = prepared.groupBy { variant ->
        // Connection-equivalent profiles are folded for display only; every GUID remains in variants.
        profileByGuid[variant.guid]?.duplicateIdentity() ?: variant.guid
    }.values.map { variants ->
        PublicServerGroup(id = variants.minOf(PublicServerVariant::guid), variants = variants)
    }

    val sorted = when (filters.sort) {
        PublicSort.COMPLETENESS -> groups.sortedWith(
            compareByDescending<PublicServerGroup> { completenessRank(it.assessment.completeness) }
                .thenByDescending { it.assessment.score }
                .thenByDescending(PublicServerGroup::sourceUpdatedAt)
                .thenBy(PublicServerGroup::id)
        )
        PublicSort.LATENCY -> groups.sortedWith(
            compareBy<PublicServerGroup> { it.bestMeasuredLatency == null }
                .thenBy { it.bestMeasuredLatency ?: Long.MAX_VALUE }
                .thenBy(PublicServerGroup::id)
        )
        PublicSort.SOURCE_UPDATED -> groups.sortedWith(
            compareByDescending<PublicServerGroup> { it.sourceUpdatedAt }
                .thenBy(PublicServerGroup::id)
        )
    }
    return PublicServerCatalog(sorted, protocolCounts, prepared.size)
}

internal fun redactPublicLabel(value: String): String = value
    .replace(Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b"), "[identifiant masqué]")
    .replace(Regex("(?i)(password|passwd|uuid|token|secret|private[_-]?key)=([^&\\s]+)")) {
        "${it.groupValues[1]}=[masqué]"
    }
    .replace(Regex("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s:]+:[^/@\\s]+@")) {
        "${it.groupValues[1]}[identifiants masqués]@"
    }

private fun completenessRank(value: PublicCompleteness): Int = when (value) {
    PublicCompleteness.COMPLETE -> 3
    PublicCompleteness.PARTIAL -> 2
    PublicCompleteness.UNVERIFIED -> 1
    PublicCompleteness.INVALID -> 0
}
