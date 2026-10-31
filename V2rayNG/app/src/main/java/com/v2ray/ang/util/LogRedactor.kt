package com.v2ray.ang.util

/** Masque les secrets courants avant affichage ou copie des journaux. */
object LogRedactor {
    private val proxyLink = Regex("(?i)\\b(?:dams|vless|vmess|trojan|ss)://[^\\s\\\"'<>]+")
    private val credentials = Regex("(?i)\\b([a-z][a-z0-9+.-]*://)[^/@\\s:]+:[^/@\\s]*@")
    private val secretParameter = Regex("(?i)([?&;\\s])((?:password|passwd|pass|pwd|token|access_token|refresh_token|auth|secret|api_key|key)=)[^&#\\s,;]+")
    private val bracketIpv6 = Regex("(?i)\\[[0-9a-f:]{2,}(?:%[a-z0-9_.-]+)?](?::\\d{1,5})?")
    private val ipv6 = Regex("(?i)(?<![0-9a-f])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-f])")
    private val uuid = Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\b")
    private val ipv4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val host = Regex("\\b(?=[a-zA-Z0-9.-]{5,253}(?::\\d{2,5})?\\b)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[A-Za-z]{2,63}(?::\\d{2,5})?\\b")
    private val jsonSecret = Regex("(?i)(\\\"(?:id|uuid|password|passwd|privateKey|shortId|token|secret|auth|key)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")")
    private val jsonHost = Regex("(?i)(\\\"(?:address|server|host|sni|serverName|server_name)\\\"\\s*:\\s*\\\")(.*?)(\\\")")

    fun redact(value: String): String = value
        .replace(proxyLink, "[lien de profil masqué]")
        .replace(credentials) { match ->
            "${match.groupValues[1]}[identifiants masqués]@"
        }
        .replace(secretParameter) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}[secret masqué]"
        }
        .replace(bracketIpv6, "[adresse IPv6 masquée]")
        .replace(ipv6, "[adresse IPv6 masquée]")
        .replace(uuid, "[identifiant masqué]")
        .replace(ipv4, "[adresse masquée]")
        .replace(host, "[serveur masqué]")

    fun redactGeneratedConfig(value: String): String = value
        .replace(jsonSecret) { "${it.groupValues[1]}[secret masqué]${it.groupValues[2]}" }
        .replace(jsonHost) { match ->
            "${match.groupValues[1]}${maskHost(match.groupValues[2])}${match.groupValues[3]}"
        }
        .let(::redact)

    fun formatConnectionFailure(protocol: String, error: String, generatedConfig: String): String =
        "protocol=$protocol\nerror=${redact(error)}\noutbound=${redactGeneratedConfig(generatedConfig)}"

    private fun maskHost(value: String): String {
        val address = value.substringBeforeLast(':', value)
        val port = value.takeIf { address != value }?.let { ":${it.substringAfterLast(':')}" }.orEmpty()
        val parts = address.split('.')
        val masked = when {
            parts.size == 4 -> "${parts.first()}.***.***.${parts.last()}"
            address.contains('.') -> address.substringBefore('.') + ".***"
            address.isBlank() -> "[hôte masqué]"
            else -> address.take(1) + "***"
        }
        return masked + port
    }
}
