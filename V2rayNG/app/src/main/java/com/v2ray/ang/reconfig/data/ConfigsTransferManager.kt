package com.v2ray.ang.reconfig.data

import com.v2ray.ang.core.XrayJsonConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.reconfig.engine.ReconfigTargetValidator
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class ConfigTransferStatus {
    ADDED,
    ALREADY_PRESENT,
    INVALID,
    FAILED,
}

internal data class ConfigTransferSummary(
    val added: Int = 0,
    val alreadyPresent: Int = 0,
    val invalid: Int = 0,
    val failed: Int = 0,
    /** Lignes à retirer de la reconfiguration : ajout réellement enregistré. */
    val removableIds: List<Long> = emptyList(),
    /**
     * Doublons laissés dans la reconfiguration : la configuration existe déjà
     * dans Configs, mais elle n'a pas été transférée par cette action.
     */
    val duplicateIds: List<Long> = emptyList(),
) {
    val transferred: Int get() = added
}

/**
 * Transfert d'un serveur préparé vers Configs.
 *
 * Le transfert utilise le modèle de données existant (`ProfileItem` +
 * `MmkvManager`) : aucun second stockage de profils n'est créé.
 *
 * Une ligne n'est retirée de la reconfiguration qu'après relecture du profil
 * enregistré. En cas d'échec, elle reste intacte.
 */
internal class ConfigsTransferManager(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun transfer(servers: List<ReconfiguredServer>): ConfigTransferSummary =
        withContext(ioDispatcher) {
            if (servers.isEmpty()) return@withContext ConfigTransferSummary()

            val existing = existingFingerprints()
            var added = 0
            var alreadyPresent = 0
            var invalid = 0
            var failed = 0
            val removable = mutableListOf<Long>()
            val duplicates = mutableListOf<Long>()

            servers.forEach { server ->
                val decoded = decodeProfile(server)
                if (decoded == null || !isTransferable(decoded)) {
                    invalid++
                    return@forEach
                }

                // La destination est l'onglet Configs (groupe local). Un profil
                // reconfiguré depuis un abonnement ou depuis la bibliothèque
                // publique ne doit pas retourner dans son groupe d'origine :
                // il deviendrait invisible pour l'utilisateur.
                val profile = decoded.copy(
                    subscriptionId = "",
                    policyGroupSubscriptionId = null,
                )

                // Le champ de configuration doit contenir un JSON Xray
                // exploitable. Les lignes créées avant cette version
                // contenaient un lien brut : on les régénère depuis le profil.
                val xrayConfig = server.transformedConfig
                    .takeIf { XrayJsonConfig.isOutboundValid(it, profile.configType) }
                    ?: XrayJsonConfig.buildOutboundJson(profile)
                if (xrayConfig == null) {
                    invalid++
                    return@forEach
                }

                val fingerprint = fingerprintOf(profile)
                if (fingerprint in existing) {
                    // Déjà dans Configs : la ligne n'est pas transférée, elle
                    // reste donc dans Reconfiguration.
                    alreadyPresent++
                    duplicates += server.id
                    return@forEach
                }

                val newGuid = runCatching {
                    MmkvManager.encodeServerConfig(Utils.getUuid(), profile)
                }.getOrNull()

                val stored = newGuid?.let { MmkvManager.decodeServerConfig(it) }
                // Relecture du profil enregistré : l'empreinte doit être
                // identique, sinon un paramètre a été perdu ou altéré.
                val storedIntact = stored != null && fingerprintOf(stored) == fingerprint
                if (newGuid.isNullOrBlank() || !storedIntact) {
                    failed++
                } else {
                    added++
                    existing += fingerprint
                    removable += server.id
                }
            }

            if (added > 0) {
                SettingsChangeManager.makeSetupGroupTab()
            }

            ConfigTransferSummary(
                added = added,
                alreadyPresent = alreadyPresent,
                invalid = invalid,
                failed = failed,
                removableIds = removable,
                duplicateIds = duplicates,
            )
        }

    fun decodeProfile(server: ReconfiguredServer): ProfileItem? {
        if (server.transformedProfileJson.isBlank()) return null
        return runCatching {
            JsonUtil.fromJsonSafe(server.transformedProfileJson, ProfileItem::class.java)
        }.getOrNull()
    }

    private fun isTransferable(profile: ProfileItem): Boolean {
        val address = profile.server?.trim().orEmpty()
        val port = profile.serverPort?.toIntOrNull()
        return address.isNotEmpty() &&
            ReconfigTargetValidator.isAddress(address) &&
            port != null && port in 1..65535 &&
            !profile.password.isNullOrBlank()
    }

    private fun existingFingerprints(): MutableSet<String> {
        val fingerprints = mutableSetOf<String>()
        MmkvManager.decodeAllServerList().forEach { guid ->
            MmkvManager.decodeServerConfig(guid)?.let { fingerprints += fingerprintOf(it) }
        }
        return fingerprints
    }

    /**
     * Identité d'un profil : deux configurations qui ne diffèrent que par leur
     * libellé, leur date ou leur groupe ne sont pas deux serveurs.
     */
    private fun fingerprintOf(profile: ProfileItem): String =
        ConfigFingerprint.of(profile) ?: ConfigFingerprint.ofFallback(profile)
}
