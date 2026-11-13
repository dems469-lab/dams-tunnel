package com.v2ray.ang.reconfig.data

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.v2ray.ang.core.XrayJsonConfig
import com.v2ray.ang.dto.entities.ProfileItem
import java.security.MessageDigest

/**
 * Identité normalisée d'une configuration.
 *
 * Deux profils qui ne diffèrent que par leur libellé, leur date d'ajout ou leur
 * groupe ne sont pas deux serveurs. L'empreinte est donc calculée sur
 * l'outbound Xray normalisé : protocole, adresse, port, identifiant, flux,
 * transport, TLS, SNI, Host, chemin et paramètres de sécurité. Une différence
 * réelle (`flow`, `alpn`, `fingerprint`, `publicKey`…) produit une empreinte
 * différente, un simple renommage non.
 */
internal object ConfigFingerprint {

    /**
     * Empreinte de l'outbound normalisé, ou `null` si le profil n'est pas
     * convertible en outbound Xray (type non pris en charge par le module).
     */
    fun of(profile: ProfileItem): String? {
        val outbound = XrayJsonConfig.buildOutboundJson(profile) ?: return null
        return try {
            sha256(canonicalize(JsonParser.parseString(outbound)))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Identité de repli pour les types sans outbound Xray. Conservée pour ne
     * jamais fusionner deux lignes faute d'empreinte.
     */
    fun ofFallback(profile: ProfileItem): String = sha256(
        listOf(
            profile.configType.name,
            profile.server.orEmpty().trim().lowercase(),
            profile.serverPort.orEmpty().trim(),
            profile.password.orEmpty(),
            profile.method.orEmpty(),
            profile.flow.orEmpty(),
            profile.network.orEmpty().lowercase(),
            profile.headerType.orEmpty().lowercase(),
            profile.security.orEmpty().lowercase(),
            profile.sni.orEmpty().lowercase(),
            profile.host.orEmpty().lowercase(),
            profile.path.orEmpty(),
            profile.serviceName.orEmpty(),
            profile.quicSecurity.orEmpty().lowercase(),
            profile.quicKey.orEmpty(),
            profile.publicKey.orEmpty(),
            profile.shortId.orEmpty(),
        ).joinToString("|"),
    )

    /** Ordre des clés neutralisé : le JSON produit reste comparable. */
    private fun canonicalize(element: JsonElement): String = when {
        element.isJsonObject -> element.asJsonObject.entrySet()
            .sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}") { (key, value) ->
                "$key=${canonicalize(value)}"
            }

        element.isJsonArray -> element.asJsonArray
            .joinToString(prefix = "[", postfix = "]") { canonicalize(it) }

        element.isJsonPrimitive -> {
            val primitive = element.asJsonPrimitive
            if (primitive.isString) "\"${primitive.asString}\"" else primitive.asString
        }

        else -> "null"
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }
}
