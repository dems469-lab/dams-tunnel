package com.v2ray.ang.util

/** Masque les secrets courants avant affichage ou copie des journaux. */
object LogRedactor {
    private val uuid = Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\b")
    private val ipv4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val host = Regex("\\b(?=[a-zA-Z0-9.-]{5,253}(?::\\d{2,5})?\\b)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[A-Za-z]{2,63}(?::\\d{2,5})?\\b")

    fun redact(value: String): String = value
        .replace(uuid, "[identifiant masqué]")
        .replace(ipv4, "[adresse masquée]")
        .replace(host, "[serveur masqué]")
}
