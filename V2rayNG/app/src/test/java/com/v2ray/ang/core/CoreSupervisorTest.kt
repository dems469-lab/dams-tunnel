package com.v2ray.ang.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Scénarios du superviseur de la phase C.
 *
 * Le superviseur ne touche ni Android ni le moteur : les décisions, le journal,
 * le comptage des relances et l'attente d'arrêt réel sont exercés tels qu'ils
 * tournent en production (`CoreServiceManager` appelle ces mêmes fonctions).
 */
class CoreSupervisorTest {

    private class Harness(policy: RecoveryLimitPolicy = RecoveryLimitPolicy()) {
        var now = 1_000_000L
        val events = mutableListOf<CoreEventRecord>()
        val supervisor = CoreSupervisor(policy = policy, clock = { now }, sink = { events += it })

        fun kinds(): List<CoreEventKind> = events.map { it.kind }

        fun last(kind: CoreEventKind): CoreEventRecord =
            events.lastOrNull { it.kind == kind } ?: error("événement absent : $kind")

        fun has(kind: CoreEventKind): Boolean = events.any { it.kind == kind }

        fun runningSession() {
            supervisor.startRequested(CoreOrigin.USER, coreRunning = false)
            supervisor.coreStarted()
            supervisor.tunnelVerified()
        }
    }

    // 1. Arrêt volontaire du cœur.
    @Test
    fun voluntaryStopIsNeverTreatedAsAnIncident() {
        val h = Harness()
        h.runningSession()

        h.supervisor.stopRequested(CoreOrigin.USER)
        assertFalse(h.supervisor.unexpectedExit("native_loop_stopped", null, CoreOrigin.WATCHDOG))
        assertEquals(CoreRuntimeState.STOPPING, h.supervisor.snapshot().state)

        h.supervisor.stopped(CoreOrigin.USER)

        assertFalse(h.has(CoreEventKind.CORE_EXIT_UNEXPECTED))
        assertEquals(CoreEventKind.CORE_STOPPED, h.kinds().last())
        assertEquals(0, h.supervisor.attemptsInWindow())
        assertTrue(
            h.supervisor.requestRecovery(CoreOrigin.WATCHDOG, "native_loop_stopped", coreRunning = false)
                is RecoveryDecision.NotExpected,
        )
    }

    // 2. Mort spontanée du cœur.
    @Test
    fun spontaneousDeathIsDetectedAndRecoveredOnce() {
        val h = Harness()
        h.runningSession()

        assertTrue(h.supervisor.unexpectedExit("native_loop_stopped", null, CoreOrigin.WATCHDOG))
        val exit = h.last(CoreEventKind.CORE_EXIT_UNEXPECTED)
        assertEquals("native_loop_stopped", exit.reason)
        assertEquals(CoreRuntimeState.EXIT_UNEXPECTED, h.supervisor.snapshot().state)
        assertEquals(CoreRuntimeState.CONNECTED, exit.previousState)

        // Une seconde détection du même épisode ne produit pas un second événement.
        assertFalse(h.supervisor.unexpectedExit("native_loop_stopped", null, CoreOrigin.WATCHDOG))
        assertEquals(1, h.kinds().count { it == CoreEventKind.CORE_EXIT_UNEXPECTED })

        val decision = h.supervisor.requestRecovery(
            CoreOrigin.WATCHDOG,
            "native_loop_stopped",
            coreRunning = false,
        )
        assertTrue(decision is RecoveryDecision.Start)
        assertEquals(1, h.supervisor.attemptsInWindow())
    }

    // 3. Exception lors du démarrage.
    @Test
    fun startFailureKeepsTheException() {
        val h = Harness()
        h.supervisor.startRequested(CoreOrigin.USER, coreRunning = false)
        h.supervisor.coreStarting(CoreOrigin.USER)
        val boom = IllegalStateException("startup failed")

        h.supervisor.coreStartFailed("core_start_failed", boom, CoreOrigin.USER)

        val failure = h.last(CoreEventKind.CORE_START_FAILED)
        assertEquals(boom, failure.throwable)
        assertEquals(CoreRuntimeState.FAILED, h.supervisor.snapshot().state)
        assertTrue(failure.format().contains("exception=java.lang.IllegalStateException"))
        assertTrue(failure.format().contains("reason=core_start_failed"))
    }

    // 4. Sortie inattendue sans exception.
    @Test
    fun unexpectedExitWithoutExceptionStillCarriesAReason() {
        val h = Harness()
        h.runningSession()

        assertTrue(h.supervisor.unexpectedExit("native_status_core_stopped", null, CoreOrigin.WATCHDOG))

        val exit = h.last(CoreEventKind.CORE_EXIT_UNEXPECTED)
        assertNull(exit.throwable)
        assertTrue(exit.format().contains("reason=native_status_core_stopped"))
        assertTrue(exit.format().contains("origin=WATCHDOG"))
    }

    // 5. Deux demandes de reconnexion simultanées.
    @Test
    fun concurrentRecoveryRequestsAreMerged() {
        val h = Harness()
        h.runningSession()

        val first = h.supervisor.requestRecovery(CoreOrigin.WATCHDOG, "native_loop_stopped", coreRunning = false)
        val second = h.supervisor.requestRecovery(CoreOrigin.NETWORK, "handover_unverified", coreRunning = false)

        assertTrue(first is RecoveryDecision.Start)
        assertEquals(RecoveryDecision.Merged, second)
        // Une seule tentative consommée, malgré deux demandes.
        assertEquals(1, h.supervisor.attemptsInWindow())
        assertTrue(h.last(CoreEventKind.RECONNECT_REQUESTED).reason!!.endsWith(":merged"))
    }

    // 6. Double demande de démarrage.
    @Test
    fun doubleStartNeverTouchesAHealthyTunnel() {
        val h = Harness()
        h.runningSession()

        assertEquals(
            StartDecision.AlreadyRunning,
            h.supervisor.startRequested(CoreOrigin.USER, coreRunning = true),
        )
        assertEquals(CoreRuntimeState.CONNECTED, h.supervisor.snapshot().state)
        assertEquals(0, h.supervisor.attemptsInWindow())

        // Démarrage déjà en cours.
        val pending = Harness()
        pending.supervisor.startRequested(CoreOrigin.USER, coreRunning = false)
        assertEquals(
            StartDecision.StartInProgress,
            pending.supervisor.startRequested(CoreOrigin.SYSTEM, coreRunning = false),
        )
    }

    // 7. Redémarrage manuel avec arrêt rapide.
    @Test
    fun manualRestartStartsOnlyAfterTheStopIsConfirmed() = runBlocking {
        val h = Harness()
        h.runningSession()
        val scope = CoroutineScope(Dispatchers.Default)
        var running = true
        var starts = 0
        var stopRequests = 0

        val outcome = performManualRestart(
            supervisor = h.supervisor,
            origin = CoreOrigin.NOTIFICATION,
            isCoreRunning = { running },
            requestStop = {
                stopRequests++
                scope.launch {
                    delay(20)
                    running = false
                }
            },
            requestStart = {
                // La relance ne doit être demandée qu'une fois le moteur arrêté.
                assertFalse(running)
                starts++
            },
            stopTimeoutMillis = 2_000,
            pollMillis = 5,
            settleMillis = 0,
        )

        assertEquals(RestartOutcome.STARTED, outcome)
        assertEquals(1, stopRequests)
        assertEquals(1, starts)
        assertEquals(
            listOf(
                CoreEventKind.CORE_STOP_REQUESTED,
                CoreEventKind.CORE_STOPPED,
                CoreEventKind.CORE_START_REQUESTED,
            ),
            h.kinds().takeLast(3),
        )
    }

    // 8. Redémarrage manuel avec arrêt lent.
    @Test
    fun manualRestartWaitsForASlowStop() = runBlocking {
        val h = Harness()
        h.runningSession()
        val scope = CoroutineScope(Dispatchers.Default)
        var running = true
        var starts = 0
        val startedAt = System.currentTimeMillis()

        val outcome = performManualRestart(
            supervisor = h.supervisor,
            origin = CoreOrigin.NOTIFICATION,
            isCoreRunning = { running },
            requestStop = {
                scope.launch {
                    delay(250)
                    running = false
                }
            },
            requestStart = { starts++ },
            stopTimeoutMillis = 5_000,
            pollMillis = 10,
            settleMillis = 0,
        )

        assertEquals(RestartOutcome.STARTED, outcome)
        assertEquals(1, starts)
        assertTrue(
            System.currentTimeMillis() - startedAt >= 200L,
            "le redémarrage doit attendre l'arrêt réel du moteur",
        )
    }

    // 9. Timeout d'arrêt : aucune seconde instance.
    @Test
    fun stopTimeoutNeverStartsASecondInstance() = runBlocking {
        val h = Harness()
        h.runningSession()
        var starts = 0

        val outcome = performManualRestart(
            supervisor = h.supervisor,
            origin = CoreOrigin.NOTIFICATION,
            isCoreRunning = { true },
            requestStop = { },
            requestStart = { starts++ },
            stopTimeoutMillis = 80,
            pollMillis = 10,
            settleMillis = 0,
        )

        assertEquals(RestartOutcome.STOP_NOT_CONFIRMED, outcome)
        assertEquals(0, starts)
        assertTrue(h.has(CoreEventKind.STOP_NOT_CONFIRMED))
        assertTrue(h.has(CoreEventKind.RECONNECT_FAILED))
        assertEquals("restart_stop_timeout", h.last(CoreEventKind.RECONNECT_FAILED).reason)
        assertEquals(CoreRuntimeState.STOPPING, h.supervisor.snapshot().state)
    }

    // 10. Limite de trois relances automatiques.
    @Test
    fun automaticAttemptsAreCappedAtThree() {
        val h = Harness()

        repeat(3) { index ->
            val decision = h.supervisor.requestRecovery(
                CoreOrigin.WATCHDOG,
                "exit_$index",
                coreRunning = false,
            )
            assertTrue(decision is RecoveryDecision.Start, "tentative ${index + 1} attendue")
            h.supervisor.recoveryFinished(false, "verification_failed")
        }
        assertEquals(3, h.supervisor.attemptsInWindow())

        val blocked = h.supervisor.requestRecovery(CoreOrigin.WATCHDOG, "exit_4", coreRunning = false)

        assertTrue(blocked is RecoveryDecision.LimitReached)
        assertEquals(3, (blocked as RecoveryDecision.LimitReached).attempts)
        assertTrue(h.has(CoreEventKind.RECONNECT_LIMIT_REACHED))
        assertEquals(3, h.supervisor.attemptsInWindow())
    }

    // 11. Réinitialisation : la fenêtre expire et une action manuelle ouvre un épisode.
    @Test
    fun attemptsExpireWithTheWindowAndAManualConnectResetsThem() {
        val h = Harness(RecoveryLimitPolicy(maxAutomaticAttempts = 1, windowMillis = 60_000L))
        val first = h.supervisor.requestRecovery(CoreOrigin.WATCHDOG, "exit", coreRunning = false)
        assertTrue(first is RecoveryDecision.Start)
        h.supervisor.recoveryFinished(false, "verification_failed")

        assertTrue(
            h.supervisor.requestRecovery(CoreOrigin.WATCHDOG, "exit", coreRunning = false)
                is RecoveryDecision.LimitReached,
        )

        // Écran stable pendant plus que la fenêtre : la tentative expirée ne compte plus.
        h.now += 60_001L
        assertTrue(
            h.supervisor.requestRecovery(CoreOrigin.WATCHDOG, "exit", coreRunning = false)
                is RecoveryDecision.Start,
        )

        // Une connexion manuelle explicite ouvre un nouvel épisode.
        h.supervisor.recoveryFinished(false, "verification_failed")
        h.now += 0L
        assertTrue(
            h.supervisor.beginFollowUpAttempt(CoreOrigin.RECONNECT, "exit", coreRunning = false)
                is RecoveryDecision.LimitReached,
        )
        h.supervisor.resetEpisode()
        assertEquals(0, h.supervisor.attemptsInWindow())
        assertTrue(
            h.supervisor.requestRecovery(CoreOrigin.USER, "manual_connect", coreRunning = false)
                is RecoveryDecision.Start,
        )
    }

    // 12. Échec de récupération.
    @Test
    fun recoveryFailureIsReportedWithoutInventingSuccess() {
        val h = Harness()
        h.runningSession()
        h.supervisor.unexpectedExit("native_loop_stopped", null, CoreOrigin.WATCHDOG)
        val decision = h.supervisor.requestRecovery(
            CoreOrigin.WATCHDOG,
            "native_loop_stopped",
            coreRunning = false,
        ) as RecoveryDecision.Start

        h.supervisor.recoveryAttemptStarted(decision.attempt)
        h.supervisor.recoveryAttemptFailed(decision.attempt, "verification_failed")
        h.supervisor.recoveryFinished(false, "verification_failed")

        assertFalse(h.supervisor.lastRecoverySucceeded())
        assertFalse(h.supervisor.isRecoveryInFlight())
        assertEquals(CoreRuntimeState.EXIT_UNEXPECTED, h.supervisor.snapshot().state)
        assertFalse(h.has(CoreEventKind.RECONNECT_SUCCEEDED))
        assertTrue(h.has(CoreEventKind.RECONNECT_FAILED))
        assertEquals(2, h.kinds().count { it == CoreEventKind.RECONNECT_FAILED })
    }

    // 13. Changement de réseau pendant une récupération.
    @Test
    fun networkChangeDuringRecoveryDoesNotStartASecondRecovery() {
        val h = Harness()
        h.runningSession()
        val decision = h.supervisor.requestRecovery(
            CoreOrigin.WATCHDOG,
            "native_loop_stopped",
            coreRunning = false,
        )
        assertTrue(decision is RecoveryDecision.Start)

        val handover = h.supervisor.requestRecovery(
            CoreOrigin.NETWORK,
            "handover_unverified",
            coreRunning = true,
        )

        assertEquals(RecoveryDecision.Merged, handover)
        // Le changement de réseau ne consomme pas une seconde tentative.
        assertEquals(1, h.supervisor.attemptsInWindow())
        assertTrue(h.last(CoreEventKind.RECONNECT_REQUESTED).reason!!.endsWith(":merged"))
    }

    // Journal complet : chaque événement du cycle de vie est atteignable.
    @Test
    fun everyLifecycleEventIsLogged() {
        val h = Harness()
        val sup = h.supervisor

        sup.startRequested(CoreOrigin.USER, coreRunning = false)
        sup.coreStarting(CoreOrigin.USER)
        sup.coreStarted()
        sup.tunnelVerified()
        sup.stopRequested(CoreOrigin.USER)
        sup.stopped(CoreOrigin.USER)

        sup.startRequested(CoreOrigin.USER, coreRunning = false)
        sup.coreStarted()
        sup.unexpectedExit("native_loop_stopped", null, CoreOrigin.WATCHDOG)

        val start = sup.requestRecovery(CoreOrigin.WATCHDOG, "native_loop_stopped", coreRunning = false)
            as RecoveryDecision.Start
        sup.recoveryAttemptStarted(start.attempt)
        sup.recoveryFinished(true, "tunnel_verified")
        repeat(2) {
            sup.requestRecovery(CoreOrigin.WATCHDOG, "exit", coreRunning = false)
            sup.recoveryFinished(false, "verification_failed")
        }
        sup.requestRecovery(CoreOrigin.WATCHDOG, "exit", coreRunning = false)
        sup.coreStartFailed("core_start_failed", IllegalStateException("boom"), CoreOrigin.USER)
        sup.stopNotConfirmed(CoreOrigin.USER, "restart_stop_timeout", 25_000L)

        assertEquals(CoreEventKind.values().toSet(), h.kinds().toSet())

        val line = h.events.first().format()
        listOf("ts=", "state=", "prev=", "origin=", "attempt=", "since_verified=").forEach { field ->
            assertTrue(line.contains(field), "champ manquant : $field")
        }
        // Aucun jeton exotique ne peut être recopié dans le journal.
        assertFalse(CoreEventRecord.sanitizeToken("evil reason\nwith guid 1234-ABCD").contains(' '))
    }

    // La sonde est la seule preuve de connexion : un démarrage natif ne suffit pas.
    @Test
    fun coreStartedIsNotAProofOfConnection() {
        val h = Harness()
        h.supervisor.startRequested(CoreOrigin.USER, coreRunning = false)
        h.supervisor.coreStarted()

        assertEquals(CoreRuntimeState.RUNNING, h.supervisor.snapshot().state)
        assertFalse(h.supervisor.lastRecoverySucceeded())
        assertNull(h.supervisor.snapshot().lastVerifiedAtMillis)

        h.supervisor.tunnelVerified()
        assertEquals(CoreRuntimeState.CONNECTED, h.supervisor.snapshot().state)
        assertNotNull(h.supervisor.snapshot().lastVerifiedAtMillis)
    }

    // L'attente d'arrêt rend immédiatement la main quand le moteur est déjà arrêté.
    @Test
    fun awaitCoreStoppedReturnsImmediatelyWhenNothingRuns() = runBlocking {
        assertTrue(awaitCoreStopped(isCoreRunning = { false }, timeoutMillis = 50, pollMillis = 5, settleMillis = 0))
        assertFalse(awaitCoreStopped(isCoreRunning = { true }, timeoutMillis = 30, pollMillis = 5, settleMillis = 0))
    }
}
