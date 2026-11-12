package com.v2ray.ang.laboratory.data

import android.content.Context
import android.content.SharedPreferences
import com.v2ray.ang.laboratory.multipath.MultipathSettings
import com.v2ray.ang.laboratory.multipath.PathRole

/**
 * Préférences du laboratoire, totalement séparées de celles du VPN classique.
 *
 * Le fichier `dams_laboratory_prefs` n'est lu ni écrit par aucun autre module :
 * une réinitialisation du laboratoire ne peut donc pas effacer les réglages,
 * les serveurs ou les abonnements de production.
 */
class LaboratoryPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun settings(): MultipathSettings = MultipathSettings(
        hybridEnabled = prefs.getBoolean(KEY_HYBRID, true),
        autoFailoverEnabled = prefs.getBoolean(KEY_AUTO_FAILOVER, true),
        probeIntervalSeconds = prefs.getInt(
            KEY_PROBE_INTERVAL,
            MultipathSettings.DEFAULT_PROBE_INTERVAL,
        ),
        failoverCooldownSeconds = prefs.getInt(
            KEY_COOLDOWN,
            MultipathSettings.DEFAULT_COOLDOWN,
        ),
        probeUrl = prefs.getString(KEY_PROBE_URL, MultipathSettings.DEFAULT_PROBE_URL)
            ?: MultipathSettings.DEFAULT_PROBE_URL,
    ).normalized()

    fun saveSettings(settings: MultipathSettings) {
        val normalized = settings.normalized()
        prefs.edit()
            .putBoolean(KEY_HYBRID, normalized.hybridEnabled)
            .putBoolean(KEY_AUTO_FAILOVER, normalized.autoFailoverEnabled)
            .putInt(KEY_PROBE_INTERVAL, normalized.probeIntervalSeconds)
            .putInt(KEY_COOLDOWN, normalized.failoverCooldownSeconds)
            .putString(KEY_PROBE_URL, normalized.probeUrl)
            .apply()
    }

    fun selectedGuid(role: PathRole): String? =
        prefs.getString(keyFor(role), null)?.takeIf { it.isNotBlank() }

    fun selection(): Map<PathRole, String?> = PathRole.entries.associateWith { selectedGuid(it) }

    fun saveSelection(role: PathRole, guid: String?) {
        prefs.edit().apply {
            if (guid.isNullOrBlank()) remove(keyFor(role)) else putString(keyFor(role), guid)
        }.apply()
    }

    fun saveSelection(selection: Map<PathRole, String?>) {
        prefs.edit().apply {
            selection.forEach { (role, guid) ->
                if (guid.isNullOrBlank()) remove(keyFor(role)) else putString(keyFor(role), guid)
            }
        }.apply()
    }

    /** Efface uniquement les préférences du laboratoire. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun keyFor(role: PathRole): String = when (role) {
        PathRole.PRIMARY -> KEY_SELECTED_PRIMARY
        PathRole.SECONDARY_B -> KEY_SELECTED_B
        PathRole.SECONDARY_C -> KEY_SELECTED_C
    }

    companion object {
        const val PREFS_NAME = "dams_laboratory_prefs"

        private const val KEY_SELECTED_PRIMARY = "selected_path_a"
        private const val KEY_SELECTED_B = "selected_path_b"
        private const val KEY_SELECTED_C = "selected_path_c"
        private const val KEY_HYBRID = "hybrid_enabled"
        private const val KEY_AUTO_FAILOVER = "auto_failover_enabled"
        private const val KEY_PROBE_INTERVAL = "probe_interval_seconds"
        private const val KEY_COOLDOWN = "failover_cooldown_seconds"
        private const val KEY_PROBE_URL = "probe_url"
    }
}
