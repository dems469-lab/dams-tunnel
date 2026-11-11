package com.v2ray.ang.reconfig.net

import android.content.Context
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.XrayJsonConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.reconfig.data.ReconfigTestState
import com.v2ray.ang.service.RealPingExecutionLimiter
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
 * Le test part du JSON Xray généré pour le profil, puis :
 * 1. construit une configuration minimale dont le proxy est le premier
 *    outbound ;
 * 2. démarre une instance temporaire du cœur ;
 * 3. exécute une vraie requête HTTP à travers cet outbound
 *    ([CoreNativeManager.measureOutboundDelay]).
 *
 * Le probe natif utilise `core.Dial` (équivalent à un SOCKS local interne),
 * retire les inbounds et n'accepte que les réponses 200/204. Aucun port local
 * fixe n'est réservé : le module ne peut pas entrer en conflit avec le SOCKS
 * de l'application.
 *
 * Le résultat n'est déclaré fonctionnel que si la requête aboutit en moins de
 * [MAX_TEST_DELAY_MS] : un simple démarrage du cœur ne suffit pas.
 */
internal class ServerConnectivityTester(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val delayUrlProvider: () -> String = { TEST_URL },
) {
    private val appContext = context.applicationContext

    suspend fun test(profile: ProfileItem): TunnelTestOutcome = withContext(ioDispatcher) {
        nativeProbeSemaphore.withPermit { performTest(profile) }
    }

    private suspend fun performTest(profile: ProfileItem): TunnelTestOutcome {
        // Le JSON est celui que le module stocke : vnext pour VLESS/VMess,
        // servers pour Trojan, SNI et Host dynamiques.
        val outbound = XrayJsonConfig.buildOutboundJson(profile)
            ?: return TunnelTestOutcome(ReconfigTestState.INVALID_CONFIG, detail = "profile")
        val config = XrayJsonConfig.buildProbeConfig(outbound)
            ?: return TunnelTestOutcome(ReconfigTestState.INVALID_CONFIG, detail = "config")

        return try {
            CoreNativeManager.initCoreEnv(appContext)
            val delay = RealPingExecutionLimiter.run(profile.configType) {
                CoreNativeManager.measureOutboundDelay(config, delayUrlProvider())
            }
            when {
                delay < 0L -> TunnelTestOutcome(ReconfigTestState.UNREACHABLE, detail = "probe")
                delay > MAX_TEST_DELAY_MS ->
                    TunnelTestOutcome(ReconfigTestState.UNREACHABLE, detail = "timeout")
                else -> TunnelTestOutcome(ReconfigTestState.FUNCTIONAL, delay)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            TunnelTestOutcome(ReconfigTestState.CORE_ERROR, detail = "core")
        }
    }

    companion object {
        const val TEMP_PROFILE_PREFIX = "reconfig-test-"

        /**
         * Concurrence maximale des tests réels : le cœur natif est gourmand en
         * mémoire et en CPU sur téléphone.
         */
        const val MAX_CONCURRENT_TESTS = 3

        /** URL de contrôle demandée par le module de reconfiguration. */
        const val TEST_URL = "https://cp.cloudflare.com/generate_204"

        /**
         * Le probe natif a son propre garde-fou ; au-delà de 5 s la réponse est
         * considérée comme un échec pour respecter le contrat du module.
         */
        const val MAX_TEST_DELAY_MS = 5_000L

        private val nativeProbeSemaphore = Semaphore(MAX_CONCURRENT_TESTS)

        /**
         * Supprime les profils temporaires laissés par une version antérieure
         * du test. Aucun profil utilisateur n'est concerné.
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
