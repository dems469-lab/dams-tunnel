package com.v2ray.ang.core

/**
 * Erreur de configuration que le moteur refuserait de toute façon : inutile de
 * relancer la reconnexion en boucle, il faut remonter le motif à l'utilisateur.
 */
class ConfigValidationException(message: String) : IllegalArgumentException(message)

/**
 * Contrôles préalables des profils Shadowsocks.
 *
 * Le moteur épinglé (Xray v1.260123.0) refuse de démarrer si la clé d'un profil
 * `2022-*` n'est pas un base64 valide de la bonne longueur. Sans ce contrôle,
 * l'app arrêtait puis relançait le cœur indéfiniment et n'affichait qu'un
 * « no traffic after startup » sans cause.
 */
object ShadowsocksConfigValidator {

    /** Taille en octets de la clé exigée par chaque méthode Shadowsocks 2022. */
    private val KEY_SIZE_BYTES = mapOf(
        "2022-blake3-aes-128-gcm" to 16,
        "2022-blake3-aes-256-gcm" to 32,
        "2022-blake3-chacha20-poly1305" to 32,
    )

    /**
     * Valide un profil Shadowsocks.
     *
     * @param method méthode de chiffrement du profil
     * @param password mot de passe (une clé, ou `cléServeur:cléUtilisateur` en 2022 multi-utilisateur)
     * @param port port du serveur
     * @param decodeKey décodeur base64 (injectable pour les tests JVM)
     * @throws ConfigValidationException si le moteur ne pourrait pas démarrer
     */
    fun validate(
        method: String?,
        password: String?,
        port: Int?,
        decodeKey: (String) -> ByteArray? = ::decodeBase64,
    ) {
        if (method.isNullOrBlank()) {
            throw ConfigValidationException("Shadowsocks : méthode de chiffrement absente")
        }
        if (password.isNullOrBlank()) {
            throw ConfigValidationException("Shadowsocks : mot de passe absent")
        }
        if (port == null || port !in 1..65535) {
            throw ConfigValidationException("Shadowsocks : port invalide ($port)")
        }

        val expectedSize = KEY_SIZE_BYTES[method.lowercase()] ?: return
        val keys = password.split(":")
        if (keys.size > 2) {
            throw ConfigValidationException(
                "Shadowsocks $method : mot de passe invalide (au plus deux clés séparées par ':')",
            )
        }
        keys.forEachIndexed { index, key ->
            val label = if (keys.size == 1) "clé" else if (index == 0) "clé serveur" else "clé utilisateur"
            val decoded = decodeKey(key)
                ?: throw ConfigValidationException(
                    "Shadowsocks $method : la $label n'est pas du base64 valide",
                )
            if (decoded.size != expectedSize) {
                throw ConfigValidationException(
                    "Shadowsocks $method : la $label fait ${decoded.size} octets, $expectedSize attendus",
                )
            }
        }
    }

    /** Décodage base64 standard puis URL-safe, comme le fait le moteur. */
    private fun decodeBase64(value: String): ByteArray? {
        val flags = android.util.Base64.NO_WRAP
        return try {
            android.util.Base64.decode(value, flags)
        } catch (_: IllegalArgumentException) {
            try {
                android.util.Base64.decode(value, flags or android.util.Base64.URL_SAFE)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
