package com.v2ray.ang.util

/** Masque les secrets courants avant affichage ou copie des journaux. */
object LogRedactor {
    private val proxyLink = Regex("(?i)\\b(?:dams|vless|vmess|trojan|ss)://[^\\s\\\"'<>]+")
    private val credentials = Regex("(?i)\\b([a-z][a-z0-9+.-]*://)[^/@\\s:]+:[^/@\\s]*@")
    private val secretParameter = Regex("(?i)([?&;\\s])((?:password|passwd|pass|pwd|token|access_token|refresh_token|auth|secret|api_key|key)=)[^&#\\s,;]+")
    private val bracketIpv6 = Regex("(?i)\\[[0-9a-f:]{2,}(?:%[a-z0-9_.-]+)?](?::\\d{1,5})?")
    private val ipv6 = Regex("(?i)(?<![0-9a-f:])(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-f:])")
    private val uuid = Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\b")
    private val ipv4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val host = Regex("\\b(?=[a-zA-Z0-9.-]{5,253}(?::\\d{2,5})?\\b)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[A-Za-z]{2,63}(?::\\d{2,5})?\\b")

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
}
