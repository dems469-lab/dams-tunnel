package com.v2ray.ang.laboratory.multipath

import android.content.Context
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.laboratory.data.LaboratoryPreferences

/**
 * Source des serveurs proposés au laboratoire.
 *
 * Les profils restent stockés par le gestionnaire de production (lecture
 * seule) : le laboratoire ne crée, ne modifie et ne supprime aucun profil.
 * Seuls les protocoles que le moteur Xray épinglé sait valider comme proxy
 * (VLESS, VMess, Trojan) sont proposés, afin qu'une sélection ne puisse pas
 * produire une configuration refusée au démarrage.
 */
class MultipathConfigRepository(
    context: Context,
    private val preferences: LaboratoryPreferences,
) {

    private val appContext = context.applicationContext

    data class LaboratoryProfile(
        val guid: String,
        val remarks: String,
        val protocol: String,
        val server: String,
        val supported: Boolean,
    )

    fun settings(): MultipathSettings = preferences.settings()

    fun saveSettings(settings: MultipathSettings) = preferences.saveSettings(settings)

    fun selection(): Map<PathRole, String?> = preferences.selection()

    fun saveSelection(role: PathRole, guid: String?) = preferences.saveSelection(role, guid)

    fun saveSelection(selection: Map<PathRole, String?>) = preferences.saveSelection(selection)

    /** Profils utilisables par le laboratoire, dans l'ordre de production. */
    fun availableProfiles(): List<LaboratoryProfile> =
        MmkvManager.decodeAllServerList()
            .asSequence()
            .distinct()
            .mapNotNull { guid -> profile(guid)?.let { guid to it } }
            .filter { (_, item) -> isSupported(item) }
            .map { (guid, item) ->
                LaboratoryProfile(
                    guid = guid,
                    remarks = item.remarks.ifBlank { item.server.orEmpty() },
                    protocol = item.configType.name,
                    server = item.server.orEmpty(),
                    supported = true,
                )
            }
            .toList()

    fun loadProfile(guid: String): ProfileItem? = profile(guid)

    fun loadProfile(role: PathRole): ProfileItem? = preferences.selectedGuid(role)?.let { profile(it) }

    fun displayName(guid: String): String =
        profile(guid)?.remarks?.takeIf { it.isNotBlank() } ?: "Serveur"

    private fun profile(guid: String): ProfileItem? =
        if (guid.isBlank()) null else MmkvManager.decodeServerConfig(guid)

    companion object {
        /**
         * L'API `XrayJsonConfig.isConfigValid` du moteur épinglé reconnaît
         * uniquement ces trois protocoles comme outbounds proxy valides.
         */
        val SUPPORTED_TYPES: Set<EConfigType> = setOf(
            EConfigType.VLESS,
            EConfigType.VMESS,
            EConfigType.TROJAN,
        )

        fun isSupported(profile: ProfileItem): Boolean =
            !profile.configType.isComplexType() && profile.configType in SUPPORTED_TYPES
    }
}
