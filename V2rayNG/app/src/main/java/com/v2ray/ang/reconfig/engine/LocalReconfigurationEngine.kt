package com.v2ray.ang.reconfig.engine

import android.util.Base64
import android.util.Log
import com.v2ray.ang.core.XrayJsonConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.net.URLDecoder
import org.json.JSONObject

/**
 * Moteur de reconfiguration local, fondé sur une manipulation de chaînes pure.
 *
 * Aucune résolution DNS et aucune connexion réseau : les liens sont découpés
 * manuellement, sans `java.net.URI`, ce qui évite les exceptions des parseurs
 * stricts sur les liens réels et accepte notamment les mots de passe Trojan
 * contenant des caractères spéciaux.
 *
 * Règles appliquées :
 * 1. MAXIT-Mobile préfixe un domaine par `Maxit-mobile.orange.bf`.
 * 2. Pour une adresse IP, le SNI (ou à défaut l'en-tête Host) sert de domaine.
 * 3. Les hôtes `api-connect.orange.bf` et `apigw-maxit2.orange.bf` ne sont
 *    jamais modifiés et un préfixe déjà présent n'est jamais répété.
 * 4. Le port de connexion est forcé à 443 et le TLS passe en `allowInsecure`
 *    pour que le JSON généré, le test réel et la connexion depuis Configs
 *    utilisent la même politique.
 * 5. Le profil d'origine n'est jamais modifié : le résultat est une copie.
 *
 * Ce moteur ne valide pas le résultat par un aller-retour de sérialisation :
 * une configuration réelle qui ne se réencode pas à l'identique reste
 * transformable. La seule vérification est structurelle (protocole, adresse,
 * cible personnalisée).
 */
internal object LocalReconfigurationEngine {

    private const val TAG = "RECONFIG"
    private const val PREFIX_MAXIT = "Maxit-mobile.orange.bf"
    private const val HOST_API_CONNECT = "api-connect.orange.bf"
    private const val HOST_API_GATEWAY = "apigw-maxit2.orange.bf"
    private const val FORCED_PORT = "443"

    private const val METHOD_MAXIT = "MAXIT"
    private const val METHOD_API_CONNECT = "API_CONNECT"
    private const val METHOD_API_GATEWAY = "API_GATEWAY"
    private const val METHOD_CUSTOM = "CUSTOM"

    private val EXCEPTIONS = listOf(HOST_API_CONNECT, HOST_API_GATEWAY)
    private val IPV4_REGEX = Regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}$")

    data class ReconfigResult(
        val success: Boolean,
        val originalLink: String,
        val newLink: String?,
        val errorMessage: String? = null,
    )

    /**
     * Transforme un lien brut VLESS, VMess ou Trojan.
     *
     * @param method `MAXIT`, `API_CONNECT`, `API_GATEWAY` ou `CUSTOM`.
     * @param customTarget domaine ou IPv4 utilisé par la méthode `CUSTOM`.
     */
    fun processLink(rawLink: String, method: String, customTarget: String = ""): ReconfigResult {
        val link = rawLink.trim()
        val result = try {
            when {
                link.startsWith("vless://") -> processUriProtocol(link, "vless", method, customTarget)
                link.startsWith("trojan://") -> processUriProtocol(link, "trojan", method, customTarget)
                link.startsWith("vmess://") -> processVmess(link, method, customTarget)
                else -> ReconfigResult(false, link, null, "Protocole non supporté")
            }
        } catch (e: Exception) {
            ReconfigResult(false, link, null, "Erreur de parsing : ${e.message}")
        }
        log("Resultat: ${result.success} - ${result.errorMessage}")
        return result
    }

    private fun processUriProtocol(
        link: String,
        protocol: String,
        method: String,
        customTarget: String,
    ): ReconfigResult {
        val rest = link.substringAfter("$protocol://")

        // L'autorité s'arrête au premier « ? » ou « # ».
        val queryIndex = rest.indexOf('?')
        val hashIndex = rest.indexOf('#')
        val authorityEnd = listOf(queryIndex, hashIndex)
            .filter { it >= 0 }
            .minOrNull() ?: rest.length
        val authority = rest.substring(0, authorityEnd)

        // Dernier « @ » de l'autorité : un mot de passe Trojan peut en contenir.
        val at = authority.lastIndexOf('@')
        if (at < 0) return ReconfigResult(false, link, null, "Format invalide (pas de @)")

        val userInfo = authority.substring(0, at)
        val hostPort = authority.substring(at + 1)

        val query = if (queryIndex >= 0 && (hashIndex < 0 || queryIndex < hashIndex)) {
            rest.substring(queryIndex, if (hashIndex >= 0) hashIndex else rest.length)
        } else {
            ""
        }
        val fragment = if (hashIndex >= 0) rest.substring(hashIndex) else ""

        val host = hostPort.substringBefore(":")
        if (host.isBlank()) return ReconfigResult(false, link, null, "Hôte absent")

        var sni = ""
        var hostParam = ""
        if (query.isNotEmpty()) {
            query.substring(1).split("&").forEach { param ->
                val kv = param.split("=", limit = 2)
                if (kv.size == 2) {
                    if (kv[0] == "sni" || kv[0] == "peer") sni = URLDecoder.decode(kv[1], "UTF-8")
                    if (kv[0] == "host") hostParam = URLDecoder.decode(kv[1], "UTF-8")
                }
            }
        }

        val newHost = calculateNewAddress(host, sni, hostParam, method, customTarget)
        val newLink = "$protocol://$userInfo@$newHost:$FORCED_PORT$query$fragment"
        return ReconfigResult(true, link, newLink)
    }

    private fun processVmess(link: String, method: String, customTarget: String): ReconfigResult {
        val base64Data = link.substringAfter("vmess://")
        val jsonString = String(Base64.decode(base64Data, Base64.DEFAULT), Charsets.UTF_8)
        val json = JSONObject(jsonString)

        val originalHost = json.optString("add")
        if (originalHost.isBlank()) return ReconfigResult(false, link, null, "Hôte absent")
        val sni = json.optString("sni")
        val hostParam = json.optString("host")

        val newHost = calculateNewAddress(originalHost, sni, hostParam, method, customTarget)

        json.put("add", newHost)
        json.put("port", FORCED_PORT)

        val newBase64 = Base64.encodeToString(
            json.toString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        return ReconfigResult(true, link, "vmess://$newBase64")
    }

    private fun calculateNewAddress(
        originalHost: String,
        sni: String,
        hostParam: String,
        method: String,
        customTarget: String,
    ): String {
        val host = originalHost.trim()
        val prefix = when (method) {
            METHOD_MAXIT -> PREFIX_MAXIT
            METHOD_API_CONNECT -> HOST_API_CONNECT
            METHOD_API_GATEWAY -> HOST_API_GATEWAY
            METHOD_CUSTOM -> customTarget.trim().removeSuffix(".")
            else -> PREFIX_MAXIT
        }.trim()

        // Un préfixe vide produirait « .domaine » : on laisse l'hôte intact.
        if (prefix.isEmpty()) return host

        // Exceptions absolues : hôtes d'API, et préfixe déjà présent.
        if (EXCEPTIONS.any { it.equals(host, ignoreCase = true) }) return host
        if (host.equals(prefix, ignoreCase = true) ||
            host.startsWith("$prefix.", ignoreCase = true)
        ) {
            return host
        }

        val isIp = host.matches(IPV4_REGEX)
        if (isIp) {
            val suffix = when {
                sni.isNotEmpty() && !sni.matches(IPV4_REGEX) -> sni
                hostParam.isNotEmpty() && !hostParam.matches(IPV4_REGEX) -> hostParam
                else -> return host
            }
            if (method == METHOD_API_CONNECT || method == METHOD_API_GATEWAY) return prefix
            return "$prefix.$suffix"
        }

        if (method == METHOD_API_CONNECT || method == METHOD_API_GATEWAY) return prefix
        return "$prefix.$host"
    }

    /**
     * Point d'entrée utilisé par l'application, où les profils sont déjà
     * décodés (MMKV / Configs).
     *
     * Il applique exactement les mêmes règles que [processLink], mais sans
     * re-sérialiser le lien : aucune configuration réelle n'est perdue à cause
     * d'un parseur qui refuserait de réécrire une URI.
     */
    internal fun reconfigure(
        profile: ProfileItem,
        method: ReconfigMethod,
        targetInput: String,
    ): ReconfigurationOutcome {
        if (profile.configType !in SUPPORTED_TYPES) {
            return failure(ReconfigurationStatus.UNSUPPORTED_PROTOCOL, profile, method, targetInput)
        }

        val address = profile.server?.trim().orEmpty()
        if (address.isEmpty() || address.any { it.isWhitespace() }) {
            return failure(ReconfigurationStatus.INVALID_ADDRESS, profile, method, address)
        }

        val target = when (method) {
            ReconfigMethod.MAXIT_MOBILE -> PREFIX_MAXIT
            ReconfigMethod.API_CONNECT -> HOST_API_CONNECT
            ReconfigMethod.API_GATEWAY -> HOST_API_GATEWAY
            ReconfigMethod.CUSTOM -> ReconfigTargetValidator.validate(targetInput).target
                ?: return failure(ReconfigurationStatus.INVALID_TARGET, profile, method, targetInput)
        }

        val newAddress = calculateNewAddress(
            originalHost = address,
            sni = profile.sni.orEmpty(),
            hostParam = profile.host.orEmpty(),
            method = method.engineKey,
            customTarget = target,
        )

        // `copy` conserve tous les autres champs : SNI, Host, chemin,
        // transport, sécurité, identifiants et champs inconnus. Le TLS est
        // marqué `insecure` pour que le JSON généré, le test réel et la
        // connexion depuis Configs utilisent exactement la même politique.
        val transformed = profile.copy(
            server = newAddress,
            serverPort = FORCED_PORT,
            insecure = if (profile.security.equals("tls", ignoreCase = true)) {
                true
            } else {
                profile.insecure
            },
        )

        // Le champ de configuration stocké est un JSON Xray complet
        // (settings.vnext pour VLESS/VMess, settings.servers pour Trojan),
        // jamais le lien brut. Le profil complet reste conservé à part pour
        // le transfert vers Configs.
        val originalConfig = XrayJsonConfig.buildOutboundJson(profile)
            ?: AppConfigurationCodec.encode(profile)
        val transformedConfig = XrayJsonConfig.buildOutboundJson(transformed)
            ?: AppConfigurationCodec.encode(transformed)

        log("Resultat: true - ${method.storageKey}")

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

    private val ReconfigMethod.engineKey: String
        get() = when (this) {
            ReconfigMethod.MAXIT_MOBILE -> METHOD_MAXIT
            ReconfigMethod.API_CONNECT -> METHOD_API_CONNECT
            ReconfigMethod.API_GATEWAY -> METHOD_API_GATEWAY
            ReconfigMethod.CUSTOM -> METHOD_CUSTOM
        }

    private fun failure(
        status: ReconfigurationStatus,
        profile: ProfileItem,
        method: ReconfigMethod,
        target: String,
    ): ReconfigurationOutcome {
        log("Resultat: false - $status")
        return ReconfigurationOutcome(
            status = status,
            original = profile,
            method = method,
            target = target,
        )
    }

    /**
     * `android.util.Log` est un stub vide dans les tests JVM : l'appel est
     * protégé pour ne jamais faire échouer une transformation.
     */
    private fun log(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private val SUPPORTED_TYPES = setOf(EConfigType.VLESS, EConfigType.VMESS, EConfigType.TROJAN)
}
