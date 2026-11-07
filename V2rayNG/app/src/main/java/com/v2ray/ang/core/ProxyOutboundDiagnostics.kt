package com.v2ray.ang.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.Utils

/**
 * Champs non sensibles de l'outbound proxy final, pour le journal de diagnostic.
 * L'adresse du serveur n'est jamais exposée : seule sa nature (IP littérale ou non) l'est.
 */
internal data class ProxyOutboundDescriptor(
    val protocol: String,
    val network: String?,
    val security: String?,
    val sni: String?,
    val host: String?,
    val alpn: String?,
    val fingerprint: String?,
    val port: Int?,
    val serverIsIp: Boolean,
) {
    fun toLogLine(): String =
        "protocol=$protocol network=${network ?: "-"} security=${security ?: "-"} " +
            "sni=${sni ?: "-"} host=${host ?: "-"} alpn=${alpn ?: "-"} " +
            "fingerprint=${fingerprint ?: "-"} port=${port ?: -1} serverIsIp=$serverIsIp " +
            "sniSet=${!sni.isNullOrBlank()} hostSet=${!host.isNullOrBlank()}"
}

/** Lit l'outbound de tag `proxy` (ou le premier) sans jamais renvoyer d'adresse ni de secret. */
internal fun proxyOutboundDescriptor(configContent: String): ProxyOutboundDescriptor? = runCatching {
    val outbounds = JsonParser.parseString(configContent)
        .takeIf { it.isJsonObject }
        ?.asJsonObject
        ?.objectOrNull("outbounds")
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?: return@runCatching null

    val outbound = outbounds
        .mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
        .firstOrNull { it.stringOrNull("tag") == AppConfig.TAG_PROXY }
        ?: outbounds.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        ?: return@runCatching null

    val stream = outbound.objectOrNull("streamSettings")
    val settings = outbound.objectOrNull("settings")
    val tls = stream?.objectOrNull("tlsSettings") ?: stream?.objectOrNull("realitySettings")
    val address = outboundAddress(settings)

    ProxyOutboundDescriptor(
        protocol = outbound.stringOrNull("protocol").orEmpty(),
        network = stream?.stringOrNull("network"),
        security = stream?.stringOrNull("security"),
        sni = tls?.stringOrNull("serverName"),
        host = transportHost(stream),
        alpn = tls?.stringArray("alpn")?.joinToString(","),
        fingerprint = tls?.stringOrNull("fingerprint"),
        port = outboundPort(settings),
        serverIsIp = address != null && Utils.isPureIpAddress(address),
    )
}.getOrNull()

private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString

private fun JsonObject.objectOrNull(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.stringArray(name: String): List<String>? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }

private fun JsonObject.firstObject(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray
        ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

private fun transportHost(stream: JsonObject?): String? {
    if (stream == null) return null
    listOf("wsSettings", "httpupgradeSettings", "xhttpSettings").forEach { key ->
        stream.objectOrNull(key)?.stringOrNull("host")?.takeIf { it.isNotBlank() }?.let { return it }
    }
    stream.objectOrNull("httpSettings")?.let { http ->
        http.stringOrNull("host")?.takeIf { it.isNotBlank() }?.let { return it }
        http.stringArray("host")?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
    }
    stream.objectOrNull("tcpSettings")
        ?.objectOrNull("request")
        ?.objectOrNull("headers")
        ?.let { headers ->
            headers.stringOrNull("Host")?.takeIf { it.isNotBlank() }?.let { return it }
            headers.stringArray("Host")?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
    return null
}

private fun outboundPort(settings: JsonObject?): Int? {
    if (settings == null) return null
    settings.get("port")?.takeIf { it.isJsonPrimitive }?.asInt?.let { return it }
    listOf("vnext", "servers").forEach { key ->
        settings.firstObject(key)?.get("port")
            ?.takeIf { it.isJsonPrimitive }?.asInt?.let { return it }
    }
    settings.firstObject("peers")
        ?.stringOrNull("endpoint")
        ?.substringAfterLast(':')
        ?.toIntOrNull()
        ?.let { return it }
    return null
}

private fun outboundAddress(settings: JsonObject?): String? {
    if (settings == null) return null
    listOf("vnext", "servers").forEach { key ->
        settings.firstObject(key)?.stringOrNull("address")?.let { return it }
    }
    settings.firstObject("peers")
        ?.stringOrNull("endpoint")
        ?.substringBeforeLast(':', "")
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    settings.get("address")?.let { address ->
        if (address.isJsonArray) {
            return address.asJsonArray.firstOrNull()
                ?.takeIf { it.isJsonPrimitive }?.asString
        }
        if (address.isJsonPrimitive) return address.asString
    }
    return null
}
