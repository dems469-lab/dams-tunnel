package com.v2ray.ang.core

import com.v2ray.ang.handler.SettingsManager

/**
 * Paramètres SOCKS figés pour la durée d'une session de tunnel.
 *
 * Le port dynamique (`PREF_DYNAMIC_SOCKS_PORT`) est tiré dans le processus qui
 * construit la configuration, et le relais hev-socks5-tunnel est construit dans
 * ce même processus : sans instantané, un rechargement du cœur relisait les
 * préférences et pouvait choisir un autre port que celui déjà utilisé par le
 * relais, ce qui coupait tout le trafic jusqu'au prochain redémarrage complet.
 *
 * L'instantané est pris au premier démarrage réel du tunnel (établissement du
 * TUN ou démarrage du service proxy/root) et n'est remplacé que par
 * [begin] avec `force = true`. Il est libéré après un arrêt confirmé.
 *
 * Ce sont des valeurs internes au processus démon : `LauncherManager` ne doit
 * jamais les régénérer depuis le processus de l'interface.
 */
internal object TunnelSession {

    private data class SocksConfig(
        val port: Int,
        val username: String?,
        val password: String?,
    )

    @Volatile
    private var current: SocksConfig? = null

    /**
     * Fige les paramètres SOCKS de la session.
     *
     * @param force true pour ouvrir une nouvelle session (nouveau TUN, nouveau
     *   service) ; false pour conserver l'instantané existant.
     */
    @Synchronized
    fun begin(force: Boolean = false) {
        if (current != null && !force) return
        current = SocksConfig(
            port = SettingsManager.getSocksPort(),
            username = SettingsManager.getSocksUsername(),
            password = SettingsManager.getSocksPassword(),
        )
    }

    /** Port SOCKS de la session (génère une session implicite si besoin). */
    fun socksPort(): Int = ensure().port

    fun socksUsername(): String? = ensure().username

    fun socksPassword(): String? = ensure().password

    /** Arrêt confirmé : la prochaine session pourra tirer un nouveau port. */
    @Synchronized
    fun clear() {
        current = null
    }

    @Synchronized
    private fun ensure(): SocksConfig {
        current?.let { return it }
        val created = SocksConfig(
            port = SettingsManager.getSocksPort(),
            username = SettingsManager.getSocksUsername(),
            password = SettingsManager.getSocksPassword(),
        )
        current = created
        return created
    }
}
