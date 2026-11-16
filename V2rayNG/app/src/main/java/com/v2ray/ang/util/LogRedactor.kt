package com.v2ray.ang.util

/**
 * Protège les seuls secrets d'authentification avant affichage ou copie.
 *
 * Le journal doit rester exploitable : hôtes, adresses IP, ports, noms de
 * classes et traces de pile sont affichés tels quels. Les règles précédentes
 * remplaçaient aussi les noms de classes Java et les domaines par
 * « [serveur masqué] », ce qui rendait toute erreur illisible et empêchait de
 * diagnostiquer un incident.
 *
 * Restent masqués : les liens de profil complets, les identifiants d'URL
 * (`user:password@`), les paramètres de type `password=`/`token=` et les UUID
 * d'authentification.
 */
object LogRedactor {
    private val proxyLink = Regex("(?i)\\b(?:dams|vless|vmess|trojan|ss)://[^\\s\\\"'<>]+")
    private val credentials = Regex("(?i)\\b([a-z][a-z0-9+.-]*://)[^/@\\s:]+:[^/@\\s]*@")
    private val secretParameter = Regex("(?i)([?&;\\s])((?:password|passwd|pass|pwd|token|access_token|refresh_token|auth|secret|api_key|key)=)[^&#\\s,;]+")
    private val uuid = Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\b")

    fun redact(value: String): String = value
        .replace(proxyLink, "[lien de profil masqué]")
        .replace(credentials) { match ->
            "${match.groupValues[1]}[identifiants masqués]@"
        }
        .replace(secretParameter) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}[secret masqué]"
        }
        .replace(uuid, "[identifiant masqué]")
}
