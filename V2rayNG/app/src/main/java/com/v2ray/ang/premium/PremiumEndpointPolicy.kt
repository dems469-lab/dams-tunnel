package com.v2ray.ang.premium

import java.net.URI
import java.util.Locale

/** Valide les domaines Premium compilés et construit des URL de souscription HTTPS. */
class PremiumEndpointPolicy(configuredBaseUrls: String) {
    private val endpoints: List<Endpoint> = configuredBaseUrls
        .split(',')
        .mapNotNull(::parseBaseUrl)
        .distinctBy { it.host }

    val isConfigured: Boolean
        get() = endpoints.isNotEmpty()

    /** L'URL préférée est placée en tête ; les autres domaines configurés servent de secours. */
    fun subscriptionUrls(codeOrUrl: String): List<String> {
        if (endpoints.isEmpty()) return emptyList()
        val input = codeOrUrl.trim()
        if (input.isEmpty()) return emptyList()

        val selectedEndpoints: List<Endpoint>
        val token: String
        if (input.startsWith("https://", ignoreCase = true) || input.contains("://")) {
            val uri = runCatching { URI(input) }.getOrNull() ?: return emptyList()
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null || uri.port != -1 && uri.port !in 1..65535
            ) return emptyList()
            val host = uri.host?.lowercase(Locale.ROOT) ?: return emptyList()
            val matchedEndpoint = endpoints.firstOrNull { it.host == host } ?: return emptyList()
            val rawToken = uri.rawPath?.takeIf { it.startsWith("/sub/") }
                ?.removePrefix("/sub/")?.takeIf(::isSafeToken) ?: return emptyList()
            if (rawToken.contains('/', ignoreCase = false) || ENCODED_PATH_SEPARATOR.containsMatchIn(rawToken)) {
                return emptyList()
            }
            token = rawToken
            selectedEndpoints = listOf(matchedEndpoint) + endpoints.filterNot { it == matchedEndpoint }
        } else {
            if (!isSafeToken(input)) return emptyList()
            token = input
            selectedEndpoints = endpoints
        }

        return selectedEndpoints.map { "${it.baseUrl}/sub/$token" }
    }

    /** Extrait un token sans le conserver dans un objet d'abonnement standard. */
    fun token(codeOrUrl: String): String? {
        val urls = subscriptionUrls(codeOrUrl)
        if (urls.isEmpty()) return null
        return runCatching { URI(urls.first()).rawPath.removePrefix("/sub/") }.getOrNull()
    }

    /** Construit les URLs de secours à partir d'un token déjà extrait. */
    fun subscriptionUrlsForToken(token: String): List<String> {
        if (endpoints.isEmpty() || !isSafeToken(token)) return emptyList()
        return endpoints.map { "${it.baseUrl}/sub/$token" }
    }

    private fun parseBaseUrl(value: String): Endpoint? {
        val candidate = value.trim().trimEnd('/')
        if (candidate.isEmpty()) return null
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.port != -1 && uri.port !in 1..65535 ||
            !uri.rawPath.isNullOrEmpty() && uri.rawPath != "/"
        ) return null
        val host = uri.host.lowercase(Locale.ROOT)
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        return Endpoint(host, "https://$host$port")
    }

    private fun isSafeToken(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_TOKEN_LENGTH && SAFE_TOKEN.matches(value) &&
            !ENCODED_PATH_SEPARATOR.containsMatchIn(value)

    private data class Endpoint(val host: String, val baseUrl: String)

    private companion object {
        const val MAX_TOKEN_LENGTH = 512
        val SAFE_TOKEN = Regex("[A-Za-z0-9._~%+=-]+")
        val ENCODED_PATH_SEPARATOR = Regex("%(2f|5c)", RegexOption.IGNORE_CASE)
    }
}
