package com.v2ray.ang.laboratory.multipath

import android.content.Context
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.XrayJsonConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.JsonUtil

/**
 * Construit la configuration Xray à trois chemins.
 *
 * Le laboratoire part toujours de la configuration complète du serveur
 * principal produite par le moteur existant ([CoreConfigManager]), puis y
 * injecte deux outbounds supplémentaires. Aucun moteur Xray complet
 * supplémentaire n'est lancé : les trois chemins vivent dans un seul processus
 * et un seul TUN.
 */
class MultipathEngine(
    context: Context,
    private val configRepository: MultipathConfigRepository,
) {

    private val appContext = context.applicationContext

    data class Prepared(
        val primaryGuid: String,
        val primaryName: String,
        val secondaryGuids: Map<PathRole, String>,
        val configJson: String,
        val targets: List<PathTarget>,
        val selector: List<String>,
        val settings: MultipathSettings,
    )

    data class PreparationFailure(val code: String)

    /**
     * Prépare la configuration. Aucun tunnel n'est arrêté ni démarré ici :
     * l'appelant décide du moment de la bascule.
     */
    fun prepare(
        primaryGuid: String,
        secondaryGuids: Map<PathRole, String>,
        settings: MultipathSettings,
    ): Result<Prepared> {
        val primary = configRepository.loadProfile(primaryGuid)
            ?: return Result.failure(PreparationException("primary_missing"))
        if (!MultipathConfigRepository.isSupported(primary)) {
            return Result.failure(PreparationException("primary_unsupported"))
        }

        val base = CoreConfigManager.getV2rayConfig(appContext, primaryGuid)
        if (!base.status || !XrayJsonConfig.isConfigValid(base.content)) {
            return Result.failure(PreparationException("primary_config_invalid"))
        }

        val outboundJsons = mutableMapOf<PathRole, String>()
        val guids = mutableMapOf<PathRole, String>()
        for (role in listOf(PathRole.SECONDARY_B, PathRole.SECONDARY_C)) {
            val guid = secondaryGuids[role]?.takeIf { it.isNotBlank() }
                ?: return Result.failure(PreparationException("secondary_missing"))
            if (guid == primaryGuid) {
                return Result.failure(PreparationException("duplicate_server"))
            }
            val profile = configRepository.loadProfile(guid)
                ?: return Result.failure(PreparationException("secondary_missing"))
            if (!MultipathConfigRepository.isSupported(profile)) {
                return Result.failure(PreparationException("secondary_unsupported"))
            }
            val outbound = buildOutbound(guid, profile)
                ?: return Result.failure(PreparationException("outbound_invalid"))
            outboundJsons[role] = outbound
            guids[role] = guid
        }

        val normalized = settings.normalized()
        val selector = if (normalized.hybridEnabled) {
            XrayMultipathConfig.allTags
        } else {
            listOf(XrayMultipathConfig.PRIMARY_TAG)
        }

        val injection = XrayMultipathConfig.inject(
            baseConfigJson = base.content,
            secondaryOutbounds = outboundJsons,
            selector = selector,
            probeUrl = normalized.probeUrl,
            observatoryIntervalSeconds = normalized.observatoryIntervalSeconds(),
        ) ?: return Result.failure(PreparationException("injection_failed"))

        if (!XrayJsonConfig.isConfigValid(injection.configJson)) {
            return Result.failure(PreparationException("config_invalid"))
        }

        val targets = buildTargets(primaryGuid, primary, guids, injection.outboundJsons)
            ?: return Result.failure(PreparationException("probe_targets_invalid"))

        return Result.success(
            Prepared(
                primaryGuid = primaryGuid,
                primaryName = primary.remarks.ifBlank { primary.server.orEmpty() },
                secondaryGuids = guids,
                configJson = injection.configJson,
                targets = targets,
                selector = selector,
                settings = normalized,
            )
        )
    }

    private fun buildTargets(
        primaryGuid: String,
        primary: ProfileItem,
        guids: Map<PathRole, String>,
        outboundJsons: Map<PathRole, String>,
    ): List<PathTarget>? {
        val bGuid = guids[PathRole.SECONDARY_B] ?: return null
        val cGuid = guids[PathRole.SECONDARY_C] ?: return null
        val bProfile = configRepository.loadProfile(bGuid) ?: return null
        val cProfile = configRepository.loadProfile(cGuid) ?: return null

        return listOf(
            target(PathRole.PRIMARY, primaryGuid, primary, outboundJsons),
            target(PathRole.SECONDARY_B, bGuid, bProfile, outboundJsons),
            target(PathRole.SECONDARY_C, cGuid, cProfile, outboundJsons),
        ).mapNotNull { it }
            .takeIf { it.size == 3 }
    }

    private fun target(
        role: PathRole,
        guid: String,
        profile: ProfileItem,
        outboundJsons: Map<PathRole, String>,
    ): PathTarget? {
        val outbound = outboundJsons[role] ?: return null
        return PathTarget(
            role = role,
            guid = guid,
            remarks = profile.remarks.ifBlank { profile.server.orEmpty() },
            protocol = profile.configType.name,
            outboundJson = outbound,
        )
    }

    /**
     * Outbound autonome d'un serveur.
     *
     * On privilégie l'extraction depuis la configuration runtime du moteur :
     * elle couvre tous les transports réellement supportés (WS, gRPC, XHTTP,
     * mKCP…). Le constructeur pur de la Reconfiguration sert de repli.
     */
    private fun buildOutbound(guid: String, profile: ProfileItem): String? {
        runCatching {
            val result = CoreConfigManager.getV2rayConfig4Speedtest(appContext, guid)
            if (result.status) {
                val root = JsonUtil.parseString(result.content)
                val outbounds = root?.getAsJsonArray("outbounds")
                val proxy = outbounds
                    ?.mapNotNull { element ->
                        element.takeIf { it.isJsonObject }?.asJsonObject
                    }
                    ?.firstOrNull { it.tagOrNull() == AppConfig.TAG_PROXY }
                if (proxy != null) {
                    val json = proxy.toString()
                    if (XrayJsonConfig.isOutboundValid(json)) return json
                }
            }
        }

        val fallback = XrayJsonConfig.buildOutboundJson(profile) ?: return null
        return fallback.takeIf { XrayJsonConfig.isOutboundValid(it) }
    }

    private fun JsonObject.tagOrNull(): String? =
        get("tag")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    class PreparationException(val code: String) : Exception(code)
}
