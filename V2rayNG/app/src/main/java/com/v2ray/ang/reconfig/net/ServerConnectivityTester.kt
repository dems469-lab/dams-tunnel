package com.v2ray.ang.reconfig.net

import android.content.Context
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.reconfig.data.ReconfigTestState
import com.v2ray.ang.service.RealPingExecutionLimiter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal data class TunnelTestOutcome(
    val state: ReconfigTestState,
    val delayMs: Long? = null,
    /**
     * Code court et non sensible, destiné à l'interface. Ne contient jamais
     * d'adresse, d'identifiant ni de paramètre de configuration.
     */
    val detail: String? = null,
)

/**
 * Test réel du tunnel d'une configuration préparée.
 *
 * Le test réutilise la chaîne du projet, celle du « vrai ping » des serveurs :
 * 1. construction de la configuration Xray via [CoreConfigManager] ;
 * 2. démarrage d'une instance temporaire du cœur ;
 * 3. requête HTTP réelle à travers cette instance ([CoreNativeManager.measureOutboundDelay]).
 *
 * Le résultat n'est déclaré fonctionnel que si la requête aboutit : un simple
 * démarrage du cœur ne suffit pas.
 *
 * Le profil est écrit sous un identifiant temporaire puis supprimé dans tous les
 * cas. L'instance temporaire du cœur ne crée aucun TUN et n'entre pas en conflit
 * avec la connexion VPN active de l'utilisateur, qui n'est jamais interrompue.
 */
internal class ServerConnectivityTester(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val delayUrlProvider: () -> String = { SettingsManager.getDelayTestUrl() },
) {
    private val appContext = context.applicationContext

    suspend fun test(profile: ProfileItem): TunnelTestOutcome = withContext(ioDispatcher) {
        nativeProbeSemaphore.withPermit { performTest(profile) }
    }

    private suspend fun performTest(profile: ProfileItem): TunnelTestOutcome {
        val tempGuid = "$TEMP_PROFILE_PREFIX${UUID.randomUUID()}"
        return try {
            CoreNativeManager.initCoreEnv(appContext)
            val storedGuid = runCatching {
                MmkvManager.encodeServerConfig(tempGuid, profile.copy(subscriptionId = ""))
            }.getOrNull() ?: return TunnelTestOutcome(ReconfigTestState.CORE_ERROR, detail = "store")

            val config = CoreConfigManager.getV2rayConfig4Speedtest(appContext, storedGuid)
            if (!config.status || config.content.isBlank()) {
                return TunnelTestOutcome(ReconfigTestState.INVALID_CONFIG, detail = "config")
            }

            val delay = RealPingExecutionLimiter.run(profile.configType) {
                CoreNativeManager.measureOutboundDelay(config.content, delayUrlProvider())
            }
            if (delay >= 0L) {
                TunnelTestOutcome(ReconfigTestState.FUNCTIONAL, delay)
            } else {
                TunnelTestOutcome(ReconfigTestState.UNREACHABLE, detail = "probe")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            TunnelTestOutcome(ReconfigTestState.CORE_ERROR, detail = "core")
        } finally {
            runCatching { MmkvManager.removeServer(tempGuid) }
        }
    }

    companion object {
        const val TEMP_PROFILE_PREFIX = "reconfig-test-"

        /**
         * Concurrence maximale des tests réels : le cœur natif est gourmand en
         * mémoire et en CPU sur téléphone.
         */
        const val MAX_CONCURRENT_TESTS = 3

        private val nativeProbeSemaphore = Semaphore(MAX_CONCURRENT_TESTS)

        /**
         * Supprime les profils temporaires laissés par un test interrompu par un
         * arrêt du processus. Aucun profil utilisateur n'est concerné.
         */
        fun purgeTemporaryProfiles() {
            runCatching {
                val temporaries = MmkvManager.decodeAllServerList()
                    .filter { it.startsWith(TEMP_PROFILE_PREFIX) }
                temporaries.forEach { MmkvManager.removeServer(it) }
            }
        }
    }
}
