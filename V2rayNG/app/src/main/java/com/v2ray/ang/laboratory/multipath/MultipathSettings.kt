package com.v2ray.ang.laboratory.multipath

/**
 * Réglages du laboratoire Multipath.
 *
 * Ils vivent uniquement dans le stockage du laboratoire : les préférences du
 * VPN classique ne sont jamais lues ni écrites ici.
 */
data class MultipathSettings(
    val hybridEnabled: Boolean = true,
    val autoFailoverEnabled: Boolean = true,
    val probeIntervalSeconds: Int = DEFAULT_PROBE_INTERVAL,
    val failoverCooldownSeconds: Int = DEFAULT_COOLDOWN,
    val probeUrl: String = DEFAULT_PROBE_URL,
) {

    fun normalized(): MultipathSettings = copy(
        probeIntervalSeconds = probeIntervalSeconds.coerceIn(MIN_PROBE_INTERVAL, MAX_PROBE_INTERVAL),
        failoverCooldownSeconds = failoverCooldownSeconds.coerceIn(MIN_COOLDOWN, MAX_COOLDOWN),
        probeUrl = probeUrl.trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: DEFAULT_PROBE_URL,
    )

    /** Intervalle de l'observatoire Xray : la bascule interne reste réactive. */
    fun observatoryIntervalSeconds(): Int = OBSERVATORY_INTERVAL_SECONDS

    fun failoverCooldownMillis(): Long = failoverCooldownSeconds * 1_000L

    companion object {
        const val DEFAULT_PROBE_URL = "https://www.gstatic.com/generate_204"

        const val MIN_PROBE_INTERVAL = 20
        const val MAX_PROBE_INTERVAL = 300
        const val DEFAULT_PROBE_INTERVAL = 45

        const val MIN_COOLDOWN = 20
        const val MAX_COOLDOWN = 180
        const val DEFAULT_COOLDOWN = 45

        /** Xray sonde les trois chemins toutes les 10 s lorsqu'il est actif. */
        const val OBSERVATORY_INTERVAL_SECONDS = 10

        /** Fenêtre glissante utilisée par [ServerQualityAnalyzer]. */
        const val SAMPLE_WINDOW = 10
    }
}
