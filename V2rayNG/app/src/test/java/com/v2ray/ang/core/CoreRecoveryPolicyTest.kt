package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Décisions pures qui décident du sort du tunnel : conserver, recharger ou
 * reconstruire.
 *
 * Ces règles tournent telles quelles en production (`CoreServiceManager`,
 * `CoreVpnService`) ; aucune dépendance Android ni native n'est nécessaire pour
 * les exercer, donc elles sont vérifiées ici sur JVM.
 */
class CoreRecoveryPolicyTest {

    // ── Sonde de santé ───────────────────────────────────────────────────────

    @Test
    fun `une seule sonde perdue ne coupe pas le tunnel`() {
        assertEquals(
            ProbeVerdict.TUNNEL_SUSPECT,
            CoreRecoveryPolicy.probeVerdict(
                reachable = false,
                consecutiveFailures = 1,
                failuresBeforeRecovery = 2,
            ),
        )
    }

    @Test
    fun `deux echecs consecutifs confirment la coupure`() {
        assertEquals(
            ProbeVerdict.TUNNEL_DOWN,
            CoreRecoveryPolicy.probeVerdict(
                reachable = false,
                consecutiveFailures = 2,
                failuresBeforeRecovery = 2,
            ),
        )
    }

    @Test
    fun `un succes efface le compteur d echecs`() {
        assertEquals(
            ProbeVerdict.TUNNEL_UP,
            CoreRecoveryPolicy.probeVerdict(
                reachable = true,
                consecutiveFailures = 0,
                failuresBeforeRecovery = 2,
            ),
        )
    }

    @Test
    fun `un seuil d un echec reste possible`() {
        assertEquals(
            ProbeVerdict.TUNNEL_DOWN,
            CoreRecoveryPolicy.probeVerdict(
                reachable = false,
                consecutiveFailures = 1,
                failuresBeforeRecovery = 1,
            ),
        )
    }

    @Test
    fun `un seuil nul est traite comme un`() {
        assertEquals(
            ProbeVerdict.TUNNEL_DOWN,
            CoreRecoveryPolicy.probeVerdict(
                reachable = false,
                consecutiveFailures = 1,
                failuresBeforeRecovery = 0,
            ),
        )
    }

    // ── Chemin de récupération ───────────────────────────────────────────────

    @Test
    fun `coeur et relais vivants rechargent le coeur seul`() {
        assertEquals(RecoveryPath.RELOAD_CORE, CoreRecoveryPolicy.recoveryPath(true, true))
    }

    @Test
    fun `coeur vivant et relais mort imposent de reconstruire le TUN`() {
        assertEquals(RecoveryPath.REBUILD_TUNNEL, CoreRecoveryPolicy.recoveryPath(true, false))
    }

    @Test
    fun `coeur mort impose une relance du service`() {
        assertEquals(RecoveryPath.RESTART_SERVICE, CoreRecoveryPolicy.recoveryPath(false, false))
        assertEquals(RecoveryPath.RESTART_SERVICE, CoreRecoveryPolicy.recoveryPath(false, true))
    }

    // ── Publication de l'état connecté ───────────────────────────────────────

    @Test
    fun `connecte seulement si la sonde le coeur et le relais sont sains`() {
        assertTrue(CoreRecoveryPolicy.canPublishConnected(true, true, true))
    }

    @Test
    fun `un relais mort interdit tout etat connecte`() {
        assertFalse(CoreRecoveryPolicy.canPublishConnected(true, true, false))
    }

    @Test
    fun `une sonde en echec interdit tout etat connecte`() {
        assertFalse(CoreRecoveryPolicy.canPublishConnected(false, true, true))
    }

    @Test
    fun `un coeur arrete interdit tout etat connecte`() {
        assertFalse(CoreRecoveryPolicy.canPublishConnected(true, false, true))
    }

    // ── Second démarrage ─────────────────────────────────────────────────────

    @Test
    fun `un tunnel utilisable est conserve au second demarrage`() {
        assertEquals(
            StartDisposition.KEEP_EXISTING,
            CoreRecoveryPolicy.startDisposition(coreRunning = true, pathUsable = true),
        )
    }

    @Test
    fun `un tunnel inutilisable est reconstruit apres arret reel`() {
        assertEquals(
            StartDisposition.REBUILD,
            CoreRecoveryPolicy.startDisposition(coreRunning = true, pathUsable = false),
        )
        assertEquals(
            StartDisposition.REBUILD,
            CoreRecoveryPolicy.startDisposition(coreRunning = false, pathUsable = true),
        )
        assertEquals(
            StartDisposition.REBUILD,
            CoreRecoveryPolicy.startDisposition(coreRunning = false, pathUsable = false),
        )
    }
}
