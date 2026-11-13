package com.v2ray.ang.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * États du cœur Xray tels que le superviseur les connaît.
 *
 * Ce ne sont pas des états d'interface : l'interface continue de recevoir les
 * messages historiques (`MSG_STATE_*`). L'état interne sert à décider quand une
 * récupération est légitime et quand elle est interdite.
 */
enum class CoreRuntimeState {
    /** Rien n'est attendu : aucun tunnel démarré ou tunnel arrêté volontairement. */
    IDLE,

    /** Un démarrage est demandé ou en cours. */
    STARTING,

    /** `startLoop()` a rendu la main : le cœur tourne, le tunnel n'est pas prouvé. */
    RUNNING,

    /** Une requête réelle a traversé le tunnel. */
    CONNECTED,

    /** Le tunnel était vérifié puis ne l'est plus : reconnexion en cours. */
    RECONNECTING,

    /** Un arrêt a été demandé, la sortie est attendue. */
    STOPPING,

    /** Le cœur est arrêté proprement. */
    STOPPED,

    /** Le cœur s'est arrêté alors que personne ne l'avait demandé. */
    EXIT_UNEXPECTED,

    /** Le moteur a refusé de démarrer. */
    FAILED,
}

/** Origine d'une demande : sert à distinguer une action utilisateur d'une relance automatique. */
enum class CoreOrigin {
    USER,
    SYSTEM,
    NOTIFICATION,
    NETWORK,
    WATCHDOG,
    PROBE,
    RECONNECT,
    LABORATORY,
    UNKNOWN,
}

/** Événements du cycle de vie, journalisés de façon structurée. */
enum class CoreEventKind {
    CORE_START_REQUESTED,
    CORE_STARTING,
    CORE_STARTED,
    CORE_START_FAILED,
    CORE_STOP_REQUESTED,
    CORE_STOPPED,
    CORE_EXIT_UNEXPECTED,
    RECONNECT_REQUESTED,
    RECONNECT_STARTED,
    RECONNECT_SUCCEEDED,
    RECONNECT_FAILED,
    RECONNECT_LIMIT_REACHED,

    /** L'arrêt n'a pas été confirmé dans le délai : aucune seconde instance n'est lancée. */
    STOP_NOT_CONFIRMED,
}

/**
 * Un événement du superviseur, formaté une seule fois puis journalisé.
 *
 * Aucun identifiant de profil, aucune adresse de serveur, aucune clé : les
 * motifs sont des codes courts contrôlés par l'appelant.
 */
data class CoreEventRecord(
    val kind: CoreEventKind,
    val timestampMillis: Long,
    val previousState: CoreRuntimeState?,
    val newState: CoreRuntimeState,
    val reason: String? = null,
    val origin: CoreOrigin? = null,
    val attemptId: Int? = null,
    val millisSinceVerified: Long? = null,
    val throwable: Throwable? = null,
) {
    /** Ligne horodatée, reconstituable sans lire le code. */
    fun format(): String = buildString {
        append("ts=").append(timestamp(timestampMillis))
        append(' ').append(kind.name)
        append(" prev=").append(previousState?.name ?: "-")
        append(" state=").append(newState.name)
        append(" origin=").append(origin?.name ?: "-")
        append(" attempt=").append(attemptId ?: "-")
        append(" since_verified=").append(millisSinceVerified?.let { "${it}ms" } ?: "-")
        val cleanReason = reason?.let { sanitizeToken(it) }?.takeIf { it.isNotEmpty() }
        if (cleanReason != null) append(" reason=").append(cleanReason)
        throwable?.let { append(" exception=").append(it.javaClass.name) }
    }

    private fun timestamp(millis: Long): String = SimpleDateFormat(FORMAT, Locale.US).format(Date(millis))

    companion object {
        private const val FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
        private const val MAX_TOKEN_LENGTH = 120

        /**
         * Réduit un motif à un jeton sans espace ni ponctuation exotique : une
         * chaîne qui aurait fuité depuis une configuration ne peut pas être
         * recopiée telle quelle dans le journal.
         */
        fun sanitizeToken(value: String): String = value
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' || it == ':' }
            .take(MAX_TOKEN_LENGTH)
    }
}

/** Une tentative de récupération suivie par le superviseur. */
data class RecoveryAttempt(
    val id: Int,
    val origin: CoreOrigin,
    val reason: String,
    val startedAtMillis: Long,
    val automatic: Boolean,
)

/**
 * Décision rendue par le superviseur à une demande de récupération.
 *
 * `Start` est la seule valeur qui autorise une opération ; tout le reste est un
 * refus explicite, jamais un démarrage silencieux.
 */
sealed class RecoveryDecision {
    data class Start(
        val attempt: RecoveryAttempt,
        val backoffMillis: Long,
    ) : RecoveryDecision()

    /** Une récupération est déjà en cours : la demande est fusionnée, pas dupliquée. */
    object Merged : RecoveryDecision()

    /** Le cœur fonctionne déjà : une demande de démarrage redondante ne doit rien casser. */
    object Healthy : RecoveryDecision()

    /** L'utilisateur a arrêté le tunnel : aucune reprise automatique. */
    object NotExpected : RecoveryDecision()

    /** Plafond de relances automatiques atteint sur la fenêtre courante. */
    data class LimitReached(
        val attempts: Int,
        val windowMillis: Long,
    ) : RecoveryDecision()
}

/** Résultat d'une demande de démarrage. */
sealed class StartDecision {
    object Accepted : StartDecision()

    /** Le cœur tourne déjà : la connexion existante est conservée. */
    object AlreadyRunning : StartDecision()

    /** Un démarrage est déjà en cours : la demande est fusionnée. */
    object StartInProgress : StartDecision()
}

/**
 * Politique de limitation des relances.
 *
 * Centralisée ici : le plafond et la fenêtre sont les seuls endroits à changer.
 * Le comptage est fenêtré dans le temps, jamais remis à zéro par un succès
 * transitoire — seule une action manuelle ouvre un nouvel épisode.
 */
data class RecoveryLimitPolicy(
    val maxAutomaticAttempts: Int = DEFAULT_MAX_AUTOMATIC_ATTEMPTS,
    val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    val backoffMillis: List<Long> = DEFAULT_BACKOFF_MILLIS,
) {
    init {
        require(maxAutomaticAttempts > 0) { "maxAutomaticAttempts must be positive" }
        require(windowMillis > 0) { "windowMillis must be positive" }
        require(backoffMillis.isNotEmpty()) { "backoffMillis must not be empty" }
    }

    /** Backoff de la n-ième tentative (0 = première). */
    fun backoffFor(attemptIndex: Int): Long = backoffMillis[attemptIndex.coerceIn(0, backoffMillis.lastIndex)]

    companion object {
        /** Trois relances automatiques par fenêtre de cinq minutes. */
        const val DEFAULT_MAX_AUTOMATIC_ATTEMPTS = 3
        const val DEFAULT_WINDOW_MILLIS = 5L * 60_000L
        val DEFAULT_BACKOFF_MILLIS = listOf(1_000L, 3_000L, 8_000L, 20_000L)
    }
}

/**
 * Superviseur du cœur Xray : machine à états, journal, limitation des relances
 * et coordination des sources de récupération.
 *
 * Aucune dépendance Android : toute la décision est testable sur JVM. Le
 * démarrage et l'arrêt réels restent dans `CoreServiceManager`.
 */
class CoreSupervisor(
    private val policy: RecoveryLimitPolicy = RecoveryLimitPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sink: (CoreEventRecord) -> Unit = {},
) {
    private val lock = Any()

    private var state: CoreRuntimeState = CoreRuntimeState.IDLE
    private var lastVerifiedAtMillis: Long? = null
    private var stopRequested = false

    /**
     * Vrai après un arrêt explicitement demandé par l'utilisateur. Faux au
     * démarrage : rien n'a encore été arrêté, et l'état `IDLE` suffit à dire
     * qu'aucun tunnel n'est attendu.
     */
    private var userStopped = false
    private var recoveryInFlight = false
    private var recoverySuccess = false
    private var attemptSeq = 0
    private val attempts = ArrayDeque<RecoveryAttempt>()

    /** Dernier motif de sortie inattendue, publié pour reconstituer une session. */
    var lastUnexpectedExitReason: String? = null
        private set

    fun state(): CoreRuntimeState = synchronized(lock) { state }

    fun isExpectingRunning(): Boolean = synchronized(lock) {
        !userStopped && state != CoreRuntimeState.IDLE && state != CoreRuntimeState.STOPPED
    }

    fun isStopRequested(): Boolean = synchronized(lock) { stopRequested }

    fun isRecoveryInFlight(): Boolean = synchronized(lock) { recoveryInFlight }

    /** Tentatives automatiques encore dans la fenêtre (les manuelles ne comptent pas). */
    fun attemptsInWindow(): Int = synchronized(lock) { pruneLocked(); automaticInWindowLocked() }

    /** Millisecondes écoulées depuis la dernière connexion réellement vérifiée. */
    fun millisSinceVerified(): Long? = synchronized(lock) { millisSinceVerifiedLocked() }

    fun snapshot(): CoreSupervisorSnapshot = synchronized(lock) {
        CoreSupervisorSnapshot(
            state = state,
            stopRequested = stopRequested,
            userStopped = userStopped,
            recoveryInFlight = recoveryInFlight,
            attemptsInWindow = automaticInWindowLocked(),
            lastVerifiedAtMillis = lastVerifiedAtMillis,
        )
    }

    // ── Démarrage ────────────────────────────────────────────────────────────

    /** Une demande de démarrage arrive (service, Always-on, notification,reprise). */
    fun startRequested(origin: CoreOrigin, coreRunning: Boolean): StartDecision = synchronized(lock) {
        when {
            coreRunning -> {
                emit(
                    CoreEventKind.CORE_START_REQUESTED,
                    reason = "already_running",
                    origin = origin,
                )
                StartDecision.AlreadyRunning
            }

            state == CoreRuntimeState.STARTING -> {
                emit(
                    CoreEventKind.CORE_START_REQUESTED,
                    reason = "already_starting",
                    origin = origin,
                )
                StartDecision.StartInProgress
            }

            else -> {
                val previous = state
                state = CoreRuntimeState.STARTING
                userStopped = false
                stopRequested = false
                recoverySuccess = false
                emit(
                    CoreEventKind.CORE_START_REQUESTED,
                    origin = origin,
                    previousState = previous,
                )
                StartDecision.Accepted
            }
        }
    }

    /** Le passage au natif commence : `startLoop()` est appelé juste après. */
    fun coreStarting(origin: CoreOrigin) = synchronized(lock) {
        val previous = state
        state = CoreRuntimeState.STARTING
        emit(CoreEventKind.CORE_STARTING, origin = origin, previousState = previous)
    }

    /** `startLoop()` a rendu la main et le cœur se dit vivant. Rien n'est prouvé encore. */
    fun coreStarted() = synchronized(lock) {
        val previous = state
        state = CoreRuntimeState.RUNNING
        // Le cœur est reparti : une sortie redevient inattendue.
        stopRequested = false
        userStopped = false
        emit(CoreEventKind.CORE_STARTED, previousState = previous)
    }

    fun coreStartFailed(reason: String, cause: Throwable?, origin: CoreOrigin) = synchronized(lock) {
        val previous = state
        state = CoreRuntimeState.FAILED
        emit(
            CoreEventKind.CORE_START_FAILED,
            reason = reason,
            origin = origin,
            previousState = previous,
            throwable = cause,
        )
    }

    // ── Arrêt ────────────────────────────────────────────────────────────────

    fun stopRequested(origin: CoreOrigin) = synchronized(lock) {
        val previous = state
        stopRequested = true
        // Un arrêt n'est volontaire que s'il vient de l'utilisateur ou de la
        // notification : une reconnexion interne ne rend pas la sortie attendue.
        if (origin == CoreOrigin.USER || origin == CoreOrigin.NOTIFICATION) {
            userStopped = true
        }
        state = CoreRuntimeState.STOPPING
        emit(CoreEventKind.CORE_STOP_REQUESTED, origin = origin, previousState = previous)
    }

    fun stopped(origin: CoreOrigin) = synchronized(lock) {
        val previous = state
        stopRequested = false
        recoveryInFlight = false
        state = CoreRuntimeState.STOPPED
        emit(CoreEventKind.CORE_STOPPED, origin = origin, previousState = previous)
    }

    /**
     * L'arrêt a échoué : le moteur peut encore tourner.
     *
     * L'état revient à « fonctionnement attendu » pour que la surveillance
     * reprenne ; aucune seconde instance n'est lancée.
     */
    fun stopFailed(reason: String) {
        synchronized(lock) {
            // Idempotent : plusieurs chemins d'échec peuvent signaler le même arrêt.
            if (!stopRequested && state != CoreRuntimeState.STOPPING) return
            val previous = state
            stopRequested = false
            if (!userStopped) state = CoreRuntimeState.RUNNING
            emit(
                CoreEventKind.STOP_NOT_CONFIRMED,
                reason = "stop_failed:$reason",
                origin = CoreOrigin.USER,
                previousState = previous,
            )
        }
    }

    /**
     * Signale que l'arrêt n'a pas été confirmé dans le délai imparti.
     *
     * Aucune seconde instance n'est lancée : l'échec est publié tel quel et
     * l'état reste cohérent (`STOPPING`).
     */
    fun stopNotConfirmed(origin: CoreOrigin, reason: String, timeoutMillis: Long) = synchronized(lock) {
        emit(
            CoreEventKind.STOP_NOT_CONFIRMED,
            reason = "$reason:${timeoutMillis}ms",
            origin = origin,
            previousState = state,
        )
    }

    /** L'utilisateur demande l'arrêt : les relances automatiques sont interdites. */
    fun markUserStop() = synchronized(lock) {
        stopRequested = true
        userStopped = true
        recoveryInFlight = false
        attempts.clear()
        state = CoreRuntimeState.STOPPED
    }

    /** Nouvelle connexion manuelle : nouvel épisode, compteurs de relance remis à zéro. */
    fun resetEpisode() = synchronized(lock) {
        userStopped = false
        stopRequested = false
        recoveryInFlight = false
        recoverySuccess = false
        attempts.clear()
    }

    // ── Détection de sortie ──────────────────────────────────────────────────

    /**
     * Le cœur n'est plus vivant alors qu'il devrait l'être.
     *
     * @return true si la sortie est réellement inattendue (l'appelant doit
     *   déclencher la récupération), false si elle était demandée.
     */
    fun unexpectedExit(reason: String, cause: Throwable?, origin: CoreOrigin): Boolean {
        synchronized(lock) {
            if (userStopped || stopRequested) return false
            if (state == CoreRuntimeState.EXIT_UNEXPECTED) return false
            if (state == CoreRuntimeState.STOPPED || state == CoreRuntimeState.IDLE) return false
            val previous = state
            state = CoreRuntimeState.EXIT_UNEXPECTED
            lastUnexpectedExitReason = reason
            emit(
                CoreEventKind.CORE_EXIT_UNEXPECTED,
                reason = reason,
                origin = origin,
                previousState = previous,
                throwable = cause,
            )
            return true
        }
    }

    // ── Vérification réelle ──────────────────────────────────────────────────

    fun tunnelVerified() = synchronized(lock) {
        val previous = state
        if (previous != CoreRuntimeState.CONNECTED) {
            lastVerifiedAtMillis = clock()
            state = CoreRuntimeState.CONNECTED
        }
        recoverySuccess = true
    }

    fun tunnelUnverified(reason: String) {
        synchronized(lock) {
            if (state != CoreRuntimeState.CONNECTED && state != CoreRuntimeState.RUNNING) return
            val previous = state
            state = CoreRuntimeState.RECONNECTING
            emit(
                CoreEventKind.RECONNECT_REQUESTED,
                reason = reason,
                origin = CoreOrigin.PROBE,
                previousState = previous,
            )
        }
    }

    // ── Récupération ─────────────────────────────────────────────────────────

    /**
     * Demande centrale de récupération, utilisée par toutes les sources :
     * sonde de santé, `NetworkMonitor`, mort du cœur, relance manuelle.
     *
     * Une seule opération peut être active. Une demande concurrente est fusionnée
     * et ne produit jamais un second redémarrage.
     */
    fun requestRecovery(
        origin: CoreOrigin,
        reason: String,
        coreRunning: Boolean,
        automatic: Boolean = true,
    ): RecoveryDecision {
        return synchronized(lock) {
            if (userStopped) {
                emit(
                    CoreEventKind.RECONNECT_REQUESTED,
                    reason = "$reason:user_stopped",
                    origin = origin,
                )
                return RecoveryDecision.NotExpected
            }
            if (recoveryInFlight) {
                emit(
                    CoreEventKind.RECONNECT_REQUESTED,
                    reason = "$reason:merged",
                    origin = origin,
                )
                return RecoveryDecision.Merged
            }
            if (coreRunning && state == CoreRuntimeState.CONNECTED) {
                emit(
                    CoreEventKind.RECONNECT_REQUESTED,
                    reason = "$reason:healthy",
                    origin = origin,
                )
                return RecoveryDecision.Healthy
            }
            pruneLocked()
            if (automatic && automaticInWindowLocked() >= policy.maxAutomaticAttempts) {
                emit(
                    CoreEventKind.RECONNECT_LIMIT_REACHED,
                    reason = "$reason:${policy.maxAutomaticAttempts}/${policy.windowMillis}ms",
                    origin = origin,
                )
                return RecoveryDecision.LimitReached(automaticInWindowLocked(), policy.windowMillis)
            }
            val attempt = nextAttemptLocked(origin, reason, automatic)
            recoveryInFlight = true
            recoverySuccess = false
            emit(
                CoreEventKind.RECONNECT_REQUESTED,
                reason = reason,
                origin = origin,
                attemptId = attempt.id,
            )
            recoveryStartDecisionLocked(attempt)
        }
    }

    /**
     * Tentative suivante d'une récupération déjà en cours.
     *
     * Applique le même plafond qu'une demande externe : une boucle de relance ne
     * peut pas contourner la limite.
     */
    fun beginFollowUpAttempt(
        origin: CoreOrigin,
        reason: String,
        coreRunning: Boolean,
    ): RecoveryDecision {
        return synchronized(lock) {
            if (userStopped) return RecoveryDecision.NotExpected
            pruneLocked()
            if (automaticInWindowLocked() >= policy.maxAutomaticAttempts) {
                emit(
                    CoreEventKind.RECONNECT_LIMIT_REACHED,
                    reason = "$reason:${policy.maxAutomaticAttempts}/${policy.windowMillis}ms",
                    origin = origin,
                )
                return RecoveryDecision.LimitReached(automaticInWindowLocked(), policy.windowMillis)
            }
            // Le cœur est revenu tout seul entre deux tentatives : ne pas le tuer.
            if (coreRunning && state == CoreRuntimeState.CONNECTED) return RecoveryDecision.Healthy
            val attempt = nextAttemptLocked(origin, reason, automatic = true)
            emit(
                CoreEventKind.RECONNECT_REQUESTED,
                reason = reason,
                origin = origin,
                attemptId = attempt.id,
            )
            recoveryStartDecisionLocked(attempt)
        }
    }

    /** Trace une demande fusionnée : aucun second démarrage n'est lancé. */
    fun noteRequestMerged(origin: CoreOrigin, reason: String) = synchronized(lock) {
        emit(
            CoreEventKind.RECONNECT_REQUESTED,
            reason = "$reason:merged",
            origin = origin,
        )
    }

    fun recoveryAttemptStarted(attempt: RecoveryAttempt) = synchronized(lock) {
        emit(
            CoreEventKind.RECONNECT_STARTED,
            reason = attempt.reason,
            origin = attempt.origin,
            attemptId = attempt.id,
        )
    }

    fun recoveryAttemptFailed(attempt: RecoveryAttempt, reason: String, cause: Throwable? = null) =
        synchronized(lock) {
            emit(
                CoreEventKind.RECONNECT_FAILED,
                reason = reason,
                origin = attempt.origin,
                attemptId = attempt.id,
                throwable = cause,
            )
        }

    /** Clôt l'épisode de récupération et journalise son issue réelle. */
    fun recoveryFinished(success: Boolean, reason: String?, cause: Throwable? = null) = synchronized(lock) {
        val previous = state
        recoveryInFlight = false
        // En cas d'échec, l'état courant reste celui du cœur : `EXIT_UNEXPECTED`
        // s'il est mort, `RECONNECTING` s'il tourne sans trafic vérifié. Aucun
        // état terminal n'est inventé, et un arrêt volontaire n'est pas annulé.
        emit(
            if (success) CoreEventKind.RECONNECT_SUCCEEDED else CoreEventKind.RECONNECT_FAILED,
            reason = reason,
            previousState = previous,
            throwable = cause,
        )
    }

    /** Vrai si la dernière récupération a abouti à un tunnel vérifié. */
    fun lastRecoverySucceeded(): Boolean = synchronized(lock) { recoverySuccess }

    fun policy(): RecoveryLimitPolicy = policy

    // ── Interne ──────────────────────────────────────────────────────────────

    private fun nextAttemptLocked(origin: CoreOrigin, reason: String, automatic: Boolean): RecoveryAttempt {
        attemptSeq++
        val attempt = RecoveryAttempt(
            id = attemptSeq,
            origin = origin,
            reason = reason,
            startedAtMillis = clock(),
            automatic = automatic,
        )
        attempts.addLast(attempt)
        return attempt
    }

    private fun automaticInWindowLocked(): Int = attempts.count { it.automatic }

    /** Construit la décision de démarrage avec le backoff de la politique. */
    private fun recoveryStartDecisionLocked(attempt: RecoveryAttempt): RecoveryDecision.Start {
        val index = (automaticInWindowLocked() - 1).coerceAtLeast(0)
        return RecoveryDecision.Start(attempt, policy.backoffFor(index))
    }

    private fun pruneLocked() {
        val threshold = clock() - policy.windowMillis
        while (attempts.isNotEmpty() && attempts.first().startedAtMillis < threshold) {
            attempts.removeFirst()
        }
    }

    private fun millisSinceVerifiedLocked(): Long? {
        val last = lastVerifiedAtMillis ?: return null
        return (clock() - last).coerceAtLeast(0L)
    }

    private fun emit(
        kind: CoreEventKind,
        reason: String? = null,
        origin: CoreOrigin? = null,
        attemptId: Int? = null,
        previousState: CoreRuntimeState? = null,
        throwable: Throwable? = null,
    ) {
        sink(
            CoreEventRecord(
                kind = kind,
                timestampMillis = clock(),
                previousState = previousState,
                newState = state,
                reason = reason,
                origin = origin,
                attemptId = attemptId,
                millisSinceVerified = millisSinceVerifiedLocked(),
                throwable = throwable,
            ),
        )
    }
}

/** Photographie du superviseur, pour les tests et le diagnostic. */
data class CoreSupervisorSnapshot(
    val state: CoreRuntimeState,
    val stopRequested: Boolean,
    val userStopped: Boolean,
    val recoveryInFlight: Boolean,
    val attemptsInWindow: Int,
    val lastVerifiedAtMillis: Long?,
)

/**
 * Attend l'arrêt **réel** du moteur avant d'autoriser un redémarrage.
 *
 * `StopLoop()` rend la main avant que le descripteur TUN et le relais soient
 * libérés : lancer une seconde instance dans cet intervalle a déjà cassé des
 * sessions. La marge [settleMillis] couvre la libération des ressources.
 */
internal suspend fun awaitCoreStopped(
    isCoreRunning: () -> Boolean,
    timeoutMillis: Long = DEFAULT_CORE_STOP_TIMEOUT_MILLIS,
    pollMillis: Long = DEFAULT_CORE_STOP_POLL_MILLIS,
    settleMillis: Long = DEFAULT_CORE_RELEASE_SETTLE_MILLIS,
): Boolean {
    if (timeoutMillis <= 0L) return !isCoreRunning()
    val stopped = withTimeoutOrNull(timeoutMillis) {
        while (isCoreRunning()) delay(pollMillis)
        true
    } ?: false
    if (!stopped) return false
    if (settleMillis > 0L) delay(settleMillis)
    return !isCoreRunning()
}

/** Issue d'un redémarrage demandé explicitement. */
internal enum class RestartOutcome {
    STARTED,

    /** L'arrêt n'a pas été confirmé : aucune seconde instance n'a été lancée. */
    STOP_NOT_CONFIRMED,

    /** La demande d'arrêt elle-même a échoué. */
    STOP_REQUEST_FAILED,

    /** L'arrêt a été confirmé mais la relance n'a pas pu être demandée. */
    START_REQUEST_FAILED,
}

/**
 * Redémarrage explicite : arrêt réel confirmé, puis une seule relance.
 *
 * L'ancien enchaînement `stopService()` ; `delay(500)` ; `startService()`
 * lançait la nouvelle instance pendant que le moteur libérait encore le TUN.
 * Ici le drapeau natif doit passer à l'arrêt, avec une marge de libération, et
 * un dépassement du délai interdit toute relance.
 *
 * Fonction partagée avec `CoreServiceManager` : ce que les tests exercent est
 * exactement le chemin de production.
 */
internal suspend fun performManualRestart(
    supervisor: CoreSupervisor,
    origin: CoreOrigin,
    isCoreRunning: () -> Boolean,
    requestStop: () -> Unit,
    requestStart: () -> Unit,
    stopTimeoutMillis: Long = DEFAULT_CORE_STOP_TIMEOUT_MILLIS,
    pollMillis: Long = DEFAULT_CORE_STOP_POLL_MILLIS,
    settleMillis: Long = DEFAULT_CORE_RELEASE_SETTLE_MILLIS,
): RestartOutcome {
    // La sortie du cœur devient attendue : elle ne sera pas requalifiée en incident.
    supervisor.stopRequested(origin)
    if (isCoreRunning()) {
        try {
            requestStop()
        } catch (e: Exception) {
            supervisor.recoveryFinished(false, "stop_request_failed", e)
            return RestartOutcome.STOP_REQUEST_FAILED
        }
    }
    if (!awaitCoreStopped(isCoreRunning, stopTimeoutMillis, pollMillis, settleMillis)) {
        // Jamais de seconde instance tant que la première n'a pas rendu la main.
        supervisor.stopNotConfirmed(origin, "restart_stop_timeout", stopTimeoutMillis)
        supervisor.recoveryFinished(false, "restart_stop_timeout")
        return RestartOutcome.STOP_NOT_CONFIRMED
    }
    supervisor.stopped(origin)
    // Une action explicite ouvre un nouvel épisode : le plafond automatique ne
    // doit pas empêcher l'utilisateur de se reconnecter.
    supervisor.startRequested(origin, coreRunning = false)
    return try {
        requestStart()
        RestartOutcome.STARTED
    } catch (e: Exception) {
        supervisor.recoveryFinished(false, "start_request_failed", e)
        RestartOutcome.START_REQUEST_FAILED
    }
}

/** Délai maximal d'attente de l'arrêt réel avant un redémarrage manuel. */
internal const val DEFAULT_CORE_STOP_TIMEOUT_MILLIS = 25_000L
internal const val DEFAULT_CORE_STOP_POLL_MILLIS = 100L
internal const val DEFAULT_CORE_RELEASE_SETTLE_MILLIS = 250L
