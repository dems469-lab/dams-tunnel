package com.v2ray.ang.reconfig.data

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
    /** Lignes à retirer de la reconfiguration : enregistrement confirmé. */
    val removableIds: List<Long> = emptyList(),
) {
    val transferred: Int get() = added + alreadyPresent
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

            servers.forEach { server ->
                val profile = decodeProfile(server)
                if (profile == null || !isTransferable(profile)) {
                    invalid++
                    return@forEach
                }

                val fingerprint = fingerprintOf(profile)
                if (fingerprint in existing) {
                    alreadyPresent++
                    removable += server.id
                    return@forEach
                }

                val newGuid = runCatching {
                    MmkvManager.encodeServerConfig(Utils.getUuid(), profile)
                }.getOrNull()

                val stored = newGuid?.let { MmkvManager.decodeServerConfig(it) }
                if (newGuid.isNullOrBlank() || stored == null) {
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
     * libellé ou leur date ne sont pas considérées comme deux serveurs.
     */
    private fun fingerprintOf(profile: ProfileItem): String =
        ReconfigurationFingerprint.of(
            listOf(
                profile.configType.name,
                profile.server.orEmpty().lowercase(),
                profile.serverPort.orEmpty(),
                profile.password.orEmpty(),
                profile.network.orEmpty().lowercase(),
                profile.security.orEmpty().lowercase(),
                profile.sni.orEmpty().lowercase(),
                profile.host.orEmpty().lowercase(),
                profile.path.orEmpty(),
                profile.serviceName.orEmpty(),
            ).joinToString("|"),
        )
}
