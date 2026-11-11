package com.v2ray.ang.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil

/**
 * Conversion entre le modèle interne « à plat » et le JSON réellement attendu
 * par Xray.
 *
 * Le modèle [V2rayConfig.OutboundBean.OutSettingsBean] conserve les champs du
 * serveur à la racine de `settings` (`address`, `port`, `id`, `password`, ...).
 * Xray, lui, exige :
 * - `settings.vnext[].users[]` pour VMess et VLESS ;
 * - `settings.servers[]` pour Trojan, Shadowsocks, SOCKS et HTTP.
 *
 * Toutes les configurations générées passent par [toXrayJson]. Les profils
 * CUSTOM fournis par l'utilisateur ne sont jamais réécrits.
 */
object XrayJsonConfig {
    private const val TAG_PROXY = "proxy"
    private const val NETWORK_TCP = "tcp"
    private const val NETWORK_WS = "ws"
    private const val NETWORK_GRPC = "grpc"
    private const val NETWORK_HTTP_UPGRADE = "httpupgrade"
    private const val NETWORK_H2 = "h2"
    private const val NETWORK_XHTTP = "xhttp"
    private const val NETWORK_KCP = "kcp"
    private const val NETWORK_QUIC = "quic"
    private const val SECURITY_TLS = "tls"
    private const val SECURITY_REALITY = "reality"

    private val IPV4 = Regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}$")

    /**
     * Sérialise une configuration runtime complète au format Xray.
     *
     * Retourne une chaîne vide si la configuration ne peut pas être convertie ;
     * l'appelant doit alors refuser de démarrer le cœur.
     */
    fun toXrayJson(config: V2rayConfig): String {
        val root = JsonUtil.parseString(JsonUtil.toJson(config)) ?: return ""
        normalizeConfig(root)
        return JsonUtil.toJsonPretty(root) ?: ""
    }

    /** Normalise un JSON de configuration déjà sérialisé (outbounds plats). */
    fun normalizeConfigJson(json: String): String {
        val root = JsonUtil.parseString(json) ?: return json
        normalizeConfig(root)
        return JsonUtil.toJsonPretty(root) ?: json
    }

    /** Convertit tous les outbounds d'une configuration en structures Xray. */
    fun normalizeConfig(root: JsonObject) {
        root.array("outbounds")?.forEach { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.let(::normalizeOutbound)
        }
    }

    /**
     * Convertit un outbound plat en structure Xray.
     *
     * Les protocoles non concernés (freedom, blackhole, wireguard, hysteria,
     * dns, ...) sont laissés intacts : leurs champs sont déjà au bon niveau.
     */
    fun normalizeOutbound(outbound: JsonObject) {
        outbound.objectOrNull("streamSettings")?.let(::normalizeWsHost)

        val protocol = outbound.string("protocol")?.lowercase() ?: return
        val settings = outbound.objectOrNull("settings") ?: JsonObject()
        val normalized = when (protocol) {
            "vmess", "vless" -> buildVnext(protocol, settings)
            "trojan" -> buildServer(settings, includeMethod = false)
            "shadowsocks" -> buildServer(settings, includeMethod = true)
            "socks", "http" -> buildServerWithUsers(settings)
            else -> null
        } ?: return

        outbound.add("settings", normalized)
    }

    /**
     * Construit le JSON d'outbound d'un profil VLESS, VMess ou Trojan.
     *
     * Règles dynamiques :
     * - SNI : `sni` puis `host` puis domaine de base ;
     * - Host WS : `host` puis SNI ;
     * - port : celui du profil (le module de reconfiguration le fixe à 443).
     *
     * Aucune résolution DNS et aucun accès aux réglages globaux : la méthode est
     * utilisable hors ligne et testable sur JVM.
     */
    fun buildOutboundJson(profile: ProfileItem): String? {
        val protocol = when (profile.configType) {
            EConfigType.VLESS -> "vless"
            EConfigType.VMESS -> "vmess"
            EConfigType.TROJAN -> "trojan"
            else -> return null
        }

        val address = profile.server?.trim().orEmpty()
        val port = profile.serverPort?.trim()?.toIntOrNull() ?: return null
        val credential = profile.password?.trim().orEmpty()
        if (address.isEmpty() || port !in 1..65535 || credential.isEmpty()) return null

        val outbound = JsonObject().apply {
            addProperty("protocol", protocol)
            add("settings", JsonObject().apply {
                addProperty("address", address)
                addProperty("port", port)
                addProperty("level", AppConfig.DEFAULT_LEVEL)
                addProperty("id", credential)
                addProperty("password", credential)
                addProperty("security", profile.method?.takeIf { it.isNotBlank() } ?: "auto")
                addProperty("encryption", profile.method?.takeIf { it.isNotBlank() } ?: "none")
                profile.flow?.takeIf { it.isNotBlank() }?.let { addProperty("flow", it) }
            })
            add("streamSettings", buildStreamSettings(profile))
        }

        normalizeOutbound(outbound)
        return JsonUtil.toJsonPretty(outbound)
    }

    /**
     * Configuration minimale pour le test réel.
     *
     * L'outbound généré est placé en premier : le probe natif
     * `measureOutboundDelay` utilise l'outbound par défaut. Les inbounds sont
     * inutiles (le probe les retire) et aucun port local fixe n'est réservé.
     */
    fun buildProbeConfig(outboundJson: String): String? {
        if (!isOutboundValid(outboundJson)) return null
        val outbound = JsonUtil.parseString(outboundJson) ?: return null
        outbound.addProperty("tag", TAG_PROXY)

        val root = JsonObject().apply {
            add("log", JsonObject().apply { addProperty("loglevel", "warning") })
            add("outbounds", JsonArray().apply {
                add(outbound)
                add(JsonObject().apply {
                    addProperty("protocol", "freedom")
                    addProperty("tag", "direct")
                })
                add(JsonObject().apply {
                    addProperty("protocol", "blackhole")
                    addProperty("tag", "block")
                })
            })
            add("routing", JsonObject().apply {
                addProperty("domainStrategy", "AsIs")
                add("rules", JsonArray())
            })
        }
        return JsonUtil.toJsonPretty(root)
    }

    /**
     * Vérifie qu'un outbound possède la structure Xray attendue pour son
     * protocole. Un `settings` plat est refusé.
     */
    fun isOutboundValid(json: String, type: EConfigType? = null): Boolean {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) return false
        val root = JsonUtil.parseString(trimmed) ?: return false
        return isOutboundValid(root, type)
    }

    /** Vérifie qu'une configuration complète contient au moins un proxy valide. */
    fun isConfigValid(json: String): Boolean {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) return false
        val root = JsonUtil.parseString(trimmed) ?: return false
        val outbounds = root.array("outbounds") ?: return false
        var proxies = 0
        for (element in outbounds) {
            val outbound = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            when (outbound.string("protocol")?.lowercase()) {
                "vmess", "vless", "trojan" -> {
                    if (!isOutboundValid(outbound, null)) return false
                    proxies++
                }
            }
        }
        return proxies > 0
    }

    // ── Structure Xray ────────────────────────────────────────────────────────

    private fun isOutboundValid(root: JsonObject, type: EConfigType?): Boolean {
        val protocol = root.string("protocol")?.lowercase() ?: return false
        val expected = when (type) {
            EConfigType.VLESS -> "vless"
            EConfigType.VMESS -> "vmess"
            EConfigType.TROJAN -> "trojan"
            else -> null
        }
        if (expected != null && protocol != expected) return false

        val settings = root.objectOrNull("settings") ?: return false
        return when (protocol) {
            "vmess", "vless" -> {
                val vnext = settings.array("vnext") ?: return false
                !settings.has("servers") && vnext.any { element ->
                    val server = element.takeIf { it.isJsonObject }?.asJsonObject
                        ?: return@any false
                    val users = server.array("users")
                    !server.string("address").isNullOrEmpty() &&
                        server.int("port") != null &&
                        users != null &&
                        users.any { userElement ->
                            val user = userElement.takeIf { it.isJsonObject }?.asJsonObject
                            !user?.string("id").isNullOrEmpty()
                        }
                }
            }

            "trojan" -> {
                val servers = settings.array("servers") ?: return false
                !settings.has("vnext") && servers.any { element ->
                    val server = element.takeIf { it.isJsonObject }?.asJsonObject
                        ?: return@any false
                    !server.string("address").isNullOrEmpty() &&
                        server.int("port") != null &&
                        !server.string("password").isNullOrEmpty()
                }
            }

            else -> false
        }
    }

    private fun buildVnext(protocol: String, settings: JsonObject): JsonObject? {
        val address = settings.string("address") ?: return null
        val port = settings.int("port") ?: return null
        val id = settings.string("id") ?: return null
        val level = settings.int("level") ?: AppConfig.DEFAULT_LEVEL

        val user = JsonObject().apply {
            addProperty("id", id)
            if (protocol == "vmess") {
                addProperty("alterId", 0)
                addProperty("security", settings.string("security") ?: "auto")
            } else {
                addProperty("encryption", settings.string("encryption") ?: "none")
                settings.string("flow")?.let { addProperty("flow", it) }
            }
            addProperty("level", level)
        }

        val server = JsonObject().apply {
            addProperty("address", address)
            addProperty("port", port)
            add("users", JsonArray().apply { add(user) })
        }

        return JsonObject().apply {
            add("vnext", JsonArray().apply { add(server) })
        }
    }

    private fun buildServer(settings: JsonObject, includeMethod: Boolean): JsonObject? {
        val address = settings.string("address") ?: return null
        val port = settings.int("port") ?: return null
        val password = settings.string("password") ?: return null
        val level = settings.int("level") ?: AppConfig.DEFAULT_LEVEL

        val server = JsonObject().apply {
            addProperty("address", address)
            addProperty("port", port)
            if (includeMethod) settings.string("method")?.let { addProperty("method", it) }
            addProperty("password", password)
            settings.string("flow")?.let { addProperty("flow", it) }
            addProperty("level", level)
            settings.get("uot")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                ?.let { add("uot", it) }
        }

        return JsonObject().apply {
            add("servers", JsonArray().apply { add(server) })
        }
    }

    private fun buildServerWithUsers(settings: JsonObject): JsonObject? {
        val address = settings.string("address") ?: return null
        val port = settings.int("port") ?: return null
        val level = settings.int("level") ?: AppConfig.DEFAULT_LEVEL

        val server = JsonObject().apply {
            addProperty("address", address)
            addProperty("port", port)
            val user = settings.string("user")
            if (user != null) {
                add("users", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("user", user)
                        settings.string("pass")?.let { addProperty("pass", it) }
                        addProperty("level", level)
                    })
                })
            } else {
                addProperty("level", level)
            }
        }

        return JsonObject().apply {
            add("servers", JsonArray().apply { add(server) })
        }
    }

    /** WS attend `headers.Host`, pas un champ `host` à la racine. */
    private fun normalizeWsHost(stream: JsonObject) {
        val ws = stream.objectOrNull("wsSettings") ?: return
        val host = ws.string("host")
        if (host != null) {
            val headers = ws.objectOrNull("headers")
            if (headers == null) {
                ws.add("headers", JsonObject().apply { addProperty("Host", host) })
            } else if (headers.string("Host") == null) {
                headers.addProperty("Host", host)
            }
        }
        ws.remove("host")
    }

    // ── Transports et sécurité ────────────────────────────────────────────────

    private fun buildStreamSettings(profile: ProfileItem): JsonObject {
        val stream = JsonObject()
        val network = profile.network?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: NETWORK_TCP
        stream.addProperty("network", network)

        val sni = resolveSni(profile)
        val host = resolveHost(profile, sni)

        when (network) {
            NETWORK_WS -> {
                val ws = JsonObject()
                ws.addProperty("path", profile.path?.takeIf { it.isNotEmpty() } ?: "/")
                if (host != null) {
                    ws.add("headers", JsonObject().apply { addProperty("Host", host) })
                }
                stream.add("wsSettings", ws)
            }

            NETWORK_GRPC -> {
                val grpc = JsonObject()
                grpc.addProperty("serviceName", profile.serviceName.orEmpty())
                profile.authority?.takeIf { it.isNotBlank() }
                    ?.let { grpc.addProperty("authority", it) }
                stream.add("grpcSettings", grpc)
            }

            NETWORK_HTTP_UPGRADE -> {
                val httpUpgrade = JsonObject()
                httpUpgrade.addProperty("path", profile.path?.takeIf { it.isNotEmpty() } ?: "/")
                host?.let { httpUpgrade.addProperty("host", it) }
                stream.add("httpupgradeSettings", httpUpgrade)
            }

            NETWORK_TCP -> stream.add("tcpSettings", JsonObject())

            NETWORK_H2, "http" -> {
                val h2 = JsonObject()
                host?.let { h2.add("host", JsonArray().apply { add(it) }) }
                h2.addProperty("path", profile.path?.takeIf { it.isNotEmpty() } ?: "/")
                stream.add("httpSettings", h2)
            }

            NETWORK_XHTTP -> {
                val xhttp = JsonObject()
                xhttp.addProperty("path", profile.path?.takeIf { it.isNotEmpty() } ?: "/")
                host?.let { xhttp.addProperty("host", it) }
                profile.xhttpMode?.takeIf { it.isNotBlank() }
                    ?.let { xhttp.addProperty("mode", it) }
                stream.add("xhttpSettings", xhttp)
            }

            NETWORK_KCP -> stream.add("kcpSettings", JsonObject())

            NETWORK_QUIC -> {
                stream.add("quicSettings", JsonObject().apply {
                    addProperty("security", profile.quicSecurity ?: "none")
                    addProperty("key", profile.quicKey.orEmpty())
                })
            }
        }

        val security = profile.security?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        when (security) {
            SECURITY_TLS -> {
                stream.addProperty("security", SECURITY_TLS)
                stream.add("tlsSettings", JsonObject().apply {
                    // Les passerelles MAXIT présentent des certificats qui ne
                    // correspondent pas toujours au nom : le test et le profil
                    // transformé utilisent donc la même politique explicite.
                    addProperty("allowInsecure", true)
                    sni?.let { addProperty("serverName", it) }
                    profile.fingerPrint?.takeIf { it.isNotBlank() }
                        ?.let { addProperty("fingerprint", it) }
                    profile.alpn?.takeIf { it.isNotBlank() }?.let { value ->
                        add("alpn", JsonArray().apply {
                            value.split(",")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .forEach { add(it) }
                        })
                    }
                })
            }

            SECURITY_REALITY -> {
                stream.addProperty("security", SECURITY_REALITY)
                stream.add("realitySettings", JsonObject().apply {
                    addProperty("publicKey", profile.publicKey.orEmpty())
                    addProperty("shortId", profile.shortId.orEmpty())
                    sni?.let { addProperty("serverName", it) }
                    profile.fingerPrint?.takeIf { it.isNotBlank() }
                        ?.let { addProperty("fingerprint", it) }
                })
            }

            null -> Unit

            else -> stream.addProperty("security", security)
        }

        return stream
    }

    private fun resolveSni(profile: ProfileItem): String? =
        listOf(profile.sni, profile.host, profile.server)
            .asSequence()
            .mapNotNull { value ->
                val trimmed = value?.trim()
                trimmed?.takeIf { it.isNotEmpty() && !isIpLike(it) }
            }
            .firstOrNull()

    private fun resolveHost(profile: ProfileItem, sni: String?): String? =
        listOf(profile.host, profile.sni, sni, profile.server)
            .asSequence()
            .mapNotNull { value ->
                val trimmed = value?.trim()
                trimmed?.takeIf { it.isNotEmpty() }
            }
            .firstOrNull()

    private fun isIpLike(value: String): Boolean =
        IPV4.matches(value) || (value.count { it == ':' } >= 2)

    // ── Helpers JSON ──────────────────────────────────────────────────────────

    private fun JsonObject.objectOrNull(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.array(name: String): JsonArray? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.string(name: String): String? =
        get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(name: String): Int? =
        get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asInt
}
