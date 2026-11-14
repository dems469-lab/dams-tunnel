package com.v2ray.ang.core

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.contracts.IDialerService
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.ConfigResult
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.SessionRttSample
import com.v2ray.ang.dto.TrafficSnapshot
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BrowserDialerMode
import com.v2ray.ang.extension.delay
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.extension.isGroupType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.MobileTrafficUsageStore
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.ConnectionFeedback
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.util.XrayConsole
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import libv2ray.CoreCallbackHandler

object CoreServiceManager {

    /**
     * Borne haute de l'arrêt : le `stopLoop` natif est bloquant et ne peut pas
     * être interrompu. Au-delà, l'arrêt est déclaré en échec avec un motif, au
     * lieu d'être abandonné silencieusement (ancien comportement : la porte
     * annulait le bloc à 5 s et plus rien n'était publié).
     */
    private const val STOP_TIMEOUT_MILLIS = 25_000L

    /**
     * Laisse au moteur le temps de publier son état interne après stopLoop() ;
     * `isRunning` peut rester vrai quelques instants sans que le tunnel soit
     * réellement opérationnel.
     */
    private const val CORE_DOWN_GRACE_MILLIS = 2_000L

    /** Borne du seul appel natif bloquant : au-delà, échec explicite. */
    private const val NATIVE_STOP_TIMEOUT_MILLIS = 20_000L

    private val lifecycleGate = CoreLoopLifecycleGate(stopTimeoutMillis = STOP_TIMEOUT_MILLIS)

    private val coreEngine: CoreEngine = CoreNativeManager.newCoreEngine(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    private var currentOutboundTags: Set<String> = emptySet()
    private var browserDialer: IDialerService? = null
    private var networkMonitor: NetworkMonitor? = null
    /**
     * Une exception non rattrapée dans une coroutine détachée atteint le handler
     * global du thread et tue le processus daemon (FATAL EXCEPTION). On la journalise
     * au lieu de laisser mourir le service.
     */
    private val coreCoroutineErrorHandler = CoroutineExceptionHandler { _, throwable ->
        LogUtil.e(AppConfig.TAG, "StartCore-Manager: uncaught error in core coroutine", throwable)
    }
    private val connectionTestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coreCoroutineErrorHandler)

    /**
     * Portée dédiée au cycle de vie du cœur (relance automatique, redémarrage
     * manuel).
     *
     * Elle est distincte de [connectionTestScope] : l'arrêt du cœur annule les
     * sondes en cours, mais ne doit jamais annuler la séquence qui le relance
     * (ancien défaut : un redémarrage manuel était annulé par l'arrêt qu'il
     * venait lui-même de demander).
     */
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coreCoroutineErrorHandler)
    private val _trafficSnapshot = MutableStateFlow(TrafficSnapshot())
    val trafficSnapshot: StateFlow<TrafficSnapshot> = _trafficSnapshot.asStateFlow()
    private var trafficStatsJob: kotlinx.coroutines.Job? = null
    private var trafficStatsDiagnosticRead = 0
    private var connectionVerificationJob: kotlinx.coroutines.Job? = null
    private var delayMeasurementJob: kotlinx.coroutines.Job? = null
    private var healthCheckJob: kotlinx.coroutines.Job? = null
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var failoverJob: kotlinx.coroutines.Job? = null

    /**
     * Superviseur central : machine à états, journal structuré, plafond de
     * relances et coordination des sources de récupération.
     *
     * `isRunning()` n'est jamais considéré comme une preuve de fonctionnement :
     * seul un aller-retour réseau réel fait passer l'état à `CONNECTED`.
     */
    private val supervisor = CoreSupervisor(sink = CoreEventLogger::sink)

    /** Surveille la durée de vie réelle de la boucle native, indépendamment de la sonde. */
    private var coreWatchdogJob: kotlinx.coroutines.Job? = null

    /** GUID du profil réellement lancé (null hors exécution). */
    @Volatile
    private var currentGuid: String? = null

    /** Un seul basculement à la fois : deux rechargements concurrents casseraient le cœur. */
    private val failoverMutex = kotlinx.coroutines.sync.Mutex()
    private var lastHealthRecordAt = 0L
    private var lastSwitchEvaluationAt = 0L

    @Volatile
    private var isReloading = false
    private var failoverAttempted = false

    @Volatile
    private var userStopped = true

    @Volatile
    private var connectionVerified = false

    /**
     * Motif d'une configuration que le moteur refuse définitivement. Tant qu'il est
     * renseigné, la boucle de reconnexion s'arrête : relancer un profil invalide en
     * boucle ne peut pas réussir et masque la vraie erreur.
     */
    @Volatile
    private var fatalConfigError: String? = null

    /** Appelé uniquement par une action explicite de l'utilisateur. */
    fun markUserStopped() {
        userStopped = true
        // Arrêt volontaire : aucune sortie du cœur ne doit être requalifiée en
        // incident, et le quota de relances repart d'un épisode neuf.
        supervisor.markUserStop()
        reconnectJob?.cancel()
        reconnectJob = null
        healthCheckJob?.cancel()
        healthCheckJob = null
        coreWatchdogJob?.cancel()
        coreWatchdogJob = null
    }

    /** Tun descriptor the core was started with, null in the proxy only and root run modes. */
    private var currentVpnInterface: ParcelFileDescriptor? = null

    /**
     * Référence FORTE sur le contrôle du service vivant.
     *
     * L'ancienne SoftReference était collectée sous pression mémoire pendant que
     * le service tournait : `ensureForeground()` ne trouvait alors plus le
     * service et n'appelait jamais `startForeground()`, d'où le crash
     * `ForegroundServiceDidNotStartInTimeException` du processus daemon, et des
     * demandes d'arrêt silencieusement ignorées. Android maintient lui-même le
     * service tant qu'il est démarré : une référence forte ne prolonge rien.
     *
     * La libération est explicite ([clearServiceControl]), après l'arrêt, car
     * la séquence d'arrêt publie ses états finaux via ce contrôle.
     */
    private val serviceControlRef = java.util.concurrent.atomic.AtomicReference<ServiceControl?>(null)
    var serviceControl: ServiceControl?
        get() = serviceControlRef.get()
        set(value) {
            serviceControlRef.set(value)
            val service = value?.getService()
            CoreNativeManager.initCoreEnv(service)
        }

    /**
     * Libère la référence si et seulement si elle désigne encore [control] :
     * un nouveau service déjà rattaché n'est jamais libéré par la destruction
     * de l'ancienne instance.
     */
    fun clearServiceControl(control: ServiceControl) {
        serviceControlRef.compareAndSet(control, null)
    }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = coreEngine.isRunning

    /** Le cœur tourne ET transporte réellement du trafic vérifié. */
    fun isConnectionVerified() = connectionVerified

    /**
     * Signale qu'une séquence d'arrêt ne s'est pas terminée.
     *
     * Le moteur peut encore tourner : la surveillance reprend pour que sa
     * disparition éventuelle ne passe pas inaperçue, et aucune relance n'est
     * autorisée avant un arrêt confirmé.
     */
    fun noteStopIncomplete(reason: String) {
        supervisor.stopFailed(reason)
        val service = getService()
        if (service != null && isRunning()) startCoreWatchdog(service)
    }

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    suspend fun startCoreLoop(
        vpnInterface: ParcelFileDescriptor?,
        origin: CoreOrigin = CoreOrigin.USER,
    ): Boolean = lifecycleGate.start {
        // Un second démarrage ne doit jamais détruire une connexion saine : la
        // décision est centralisée et le démarrage est idempotent.
        when (val decision = supervisor.startRequested(origin, coreRunning = isRunning())) {
            StartDecision.AlreadyRunning -> {
                LogUtil.w(
                    AppConfig.TAG,
                    "StartCore-Manager: Core already running, keeping the existing tunnel",
                )
                return@start true
            }

            StartDecision.StartInProgress -> {
                // Un démarrage est déjà en cours : ne rien lancer en double et ne
                // pas signaler un échec, qui ferait arrêter le VPN en préparation.
                LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core start already in progress")
                return@start true
            }

            StartDecision.Accepted -> Unit
        }

        val service = getService()
        if (service == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            supervisor.coreStartFailed("service_unavailable", null, origin)
            return@start false
        }

        try {
            userStopped = false
            failoverAttempted = false
            connectionVerified = false
            fatalConfigError = null
            doStartCoreLoop(service, vpnInterface, origin)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            val reason = if (e is ConfigValidationException) "config_invalid" else "core_start_failed"
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
            supervisor.coreStartFailed(reason, e, origin)
            MessageHelper.sendMsg2UI(
                service,
                AppConfig.MSG_STATE_START_FAILURE,
                message,
                null,
                reason,
            )
            NotificationManager.cancelNotification()
            false
        }
    }

    @Throws(Exception::class)
    private fun doStartCoreLoop(
        service: Service,
        vpnInterface: ParcelFileDescriptor?,
        origin: CoreOrigin,
    ) {
        // Le récepteur est déjà branché à la création du service : un arrêt ou
        // une interrogation d'état est donc toujours traité, même si la boucle
        // du cœur n'a pas démarré (ancien trou : aucune réponse, état « inconnu »).
        attachServiceReceiver(service)

        currentVpnInterface = vpnInterface
        launchCore(service, vpnInterface, origin = origin)
        startNetworkMonitor(service)
    }

    /** Service auquel le récepteur de messages est actuellement rattaché. */
    @Volatile
    private var receiverService: Service? = null

    /**
     * Branche le récepteur de messages du démon. Appelé depuis
     * [com.v2ray.ang.service.CoreVpnService.onCreate], donc indépendamment de
     * l'état du cœur Xray.
     */
    @Synchronized
    fun attachServiceReceiver(service: Service) {
        if (receiverService != null) return
        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE).apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            ContextCompat.registerReceiver(service, mMsgReceive, filter, Utils.receiverFlags())
            receiverService = service
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Message receiver attached")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to register receiver", e)
        }
    }

    @Synchronized
    fun detachServiceReceiver(service: Service) {
        if (receiverService !== service) return
        receiverService = null
        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
        }
    }

    @Throws(Exception::class)
    private fun launchCore(
        service: Service,
        vpnInterface: ParcelFileDescriptor?,
        isReload: Boolean = false,
        origin: CoreOrigin = CoreOrigin.UNKNOWN,
    ) {
        // Le laboratoire est strictement optionnel : en mode normal cette
        // lecture ne trouve aucune session autorisée et le comportement reste
        // identique. Une session périmée (sans jeton de démarrage) est purgée.
        val laboratory = if (SettingsManager.isVpnMode()) LaboratoryTunnelBridge.readForStart(service) else null

        val guid = laboratory?.primaryGuid ?: MmkvManager.getSelectServer() ?: error("No server selected")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")

        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for profile $guid")
        val result = if (laboratory != null) {
            // Configuration complète préparée par le laboratoire. Aucune
            // préférence de production n'est lue ni modifiée pour l'occasion.
            if (!XrayJsonConfig.isConfigValid(laboratory.configJson)) {
                error("Laboratory configuration is invalid")
            }
            ConfigResult(status = true, guid = guid, content = laboratory.configJson)
        } else {
            CoreConfigManager.getV2rayConfig(service, guid)
        }
        LogUtil.d(AppConfig.TAG, "Generated Xray configuration for profile $guid")
        if (!result.status) {
            error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }

        currentOutboundTags = outboundTagsFromConfig(result.content)

        currentConfig = if (laboratory != null) {
            config.copy(remarks = LaboratoryTunnelBridge.laboratoryRemarks(config.remarks))
        } else {
            config
        }
        currentGuid = guid
        var tunFd = vpnInterface?.fd ?: 0
        val dialerMode = BrowserDialerMode.from(config.browserDialerMode)
        val dialerAddr = if (dialerMode != null) {
            "127.0.0.1:${Utils.findRandomFreePort()}"
        } else {
            ""
        }
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        NotificationManager.showNotification(currentConfig)
        if (dialerAddr.isNotNullEmpty()) {
            CoreNativeManager.reconcileBrowserDialer(dialerAddr)
        }
        // Le passage au natif est le seul endroit où la boucle démarre : c'est
        // ici que l'événement de démarrage est daté.
        supervisor.coreStarting(origin)
        coreEngine.start(result.content, tunFd)

        if (!isRunning()) {
            error("Core failed to start")
        }
        supervisor.coreStarted()

        LaboratoryTunnelBridge.writeStatus(
            service,
            LaboratoryTunnelBridge.STATE_RUNNING,
            if (laboratory != null) {
                LaboratoryTunnelBridge.MODE_LABORATORY
            } else {
                LaboratoryTunnelBridge.MODE_CLASSIC
            },
        )

        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }
        when (dialerMode) {
            BrowserDialerMode.OKHTTP -> {
                browserDialer = DialerNativeService()
                browserDialer!!.start(service, dialerAddr)
            }

            BrowserDialerMode.WEBVIEW -> {
                browserDialer = DialerWebviewService()
                browserDialer!!.start(service, dialerAddr)
            }

            else -> {}
        }

        if (!isReload) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_CONNECTING, "")
            NotificationManager.updateState(service.getString(com.v2ray.ang.R.string.connection_connecting), android.graphics.Color.rgb(245, 196, 81))
        } else {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_CONNECTING, "")
        }
        verifyConnection(service)
        startTrafficStats(service)
        startCoreWatchdog(service)
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
    }

    /**
     * Surveille la durée de vie réelle du cœur.
     *
     * L'API native de l'AAR ne prévient pas d'une disparition de la boucle :
     * `isRunning` reste faux sans notification et `shutdown()` du callback
     * n'est jamais invoqué. Une scrutation à 1 Hz de ce drapeau, tant qu'un
     * fonctionnement est attendu, est le seul signal disponible ; la preuve de
     * fonctionnement reste la sonde réseau de [startHealthCheck].
     */
    private fun startCoreWatchdog(service: Service) {
        if (coreWatchdogJob?.isActive == true) return
        coreWatchdogJob = connectionTestScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MILLIS)
                if (!isActive) break
                // Un arrêt demandé n'est pas un incident.
                if (userStopped || supervisor.isStopRequested()) continue
                if (!supervisor.isExpectingRunning()) continue
                if (!isRunning()) {
                    // Un arrêt en cours (rechargement, arrêt demandé) n'est pas un
                    // incident : la surveillance continue au lieu de s'arrêter.
                    if (onUnexpectedCoreExit(service, "native_loop_stopped", null)) return@launch
                }
            }
        }
    }

    /**
     * Traite une sortie que personne n'a demandée : un seul événement par
     * épisode, puis une demande de récupération centralisée.
     */
    private fun onUnexpectedCoreExit(
        service: Service,
        reason: String,
        cause: Throwable?,
        origin: CoreOrigin = CoreOrigin.WATCHDOG,
    ): Boolean {
        if (!supervisor.unexpectedExit(reason, cause, origin)) return false
        // Aucune exception inventée : le moteur peut sortir sans lever d'erreur.
        if (cause != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: unexpected core exit ($reason)", cause)
        } else {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: unexpected core exit ($reason), no exception")
        }
        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_RECONNECTING, "")
        NotificationManager.updateState(
            service.getString(com.v2ray.ang.R.string.connection_reconnecting),
            android.graphics.Color.rgb(245, 196, 81),
        )
        XrayConsole.event("Core", "Unexpected core exit: $reason")
        scheduleReconnect(service, origin, reason)
        return true
    }

    /**
     * Xray can report that its loop started before the first real request succeeds.
     * Only publish CONNECTED after a request has crossed the running core.
     */
    private fun verifyConnection(service: Service) {
        connectionVerificationJob?.cancel()
        connectionVerificationJob = connectionTestScope.launch {
            var delayMillis = -1L
            var lastError: String? = null
            for (attempt in 0 until 2) {
                if (attempt > 0) delay(1500L)
                try {
                    delayMillis = measureDelayWithFallback()
                    if (delayMillis >= 0) break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e.message
                    LogUtil.w(AppConfig.TAG, "StartCore-Manager: connectivity verification failed", e)
                }
            }
            val relayAlive = isTunnelPathAlive()
            if (CoreRecoveryPolicy.canPublishConnected(
                    probeReachable = delayMillis >= 0,
                    coreRunning = isRunning(),
                    relayRunning = relayAlive,
                )
            ) {
                connectionVerified = true
                // Connecté uniquement après une requête réelle : `startLoop()`
                // qui rend la main ne prouve rien.
                supervisor.tunnelVerified()
                failoverAttempted = false
                recordActiveHealth(delayMillis)
                // Première latence réelle disponible immédiatement pour l'accueil.
                MessageHelper.sendMsg2UI(
                    service,
                    AppConfig.MSG_SESSION_RTT,
                    SessionRttSample(delayMillis, System.currentTimeMillis(), true),
                )
                startHealthCheck(service)
                NotificationManager.updateState(service.getString(com.v2ray.ang.R.string.connection_connected), android.graphics.Color.rgb(34, 211, 238))
                XrayConsole.event("Core", "Connection verified, tunnel is up")
                ConnectionFeedback.onConnected(service)
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
            } else if (isRunning()) {
                connectionVerified = false
                supervisor.tunnelUnverified(
                    if (relayAlive) "initial_probe_failed" else "tunnel_relay_down",
                )
                recordActiveHealth(-1L)
                if (!failoverAttempted && relayAlive) {
                    // Hors de cette coroutine : le rechargement annule la
                    // vérification courante, la bascule doit lui survivre.
                    failoverJob = connectionTestScope.launch {
                        try {
                            if (!tryFailover(service)) scheduleReconnect(service)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            LogUtil.e(AppConfig.TAG, "Failover: boucle interrompue", e)
                            scheduleReconnect(service)
                        }
                    }
                    return@launch
                }
                val message = service.getString(com.v2ray.ang.R.string.connection_verification_failed)
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: no traffic after startup: ${lastError ?: "unknown error"}")
                // The core is still alive and the user did not stop it.  Keep the
                // lifecycle in reconnecting state so the UI does not present a
                // terminal failure while the backoff loop is still working.
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_RECONNECTING, message)
                NotificationManager.updateState(
                    service.getString(com.v2ray.ang.R.string.connection_reconnecting),
                    android.graphics.Color.rgb(245, 196, 81),
                )
                scheduleReconnect(service)
            }
        }
    }

    private fun startHealthCheck(service: Service) {
        healthCheckJob?.cancel()
        lastHealthRecordAt = 0L
        lastSwitchEvaluationAt = System.currentTimeMillis()
        healthCheckJob = connectionTestScope.launch {
            // Une sonde perdue n'est pas une coupure : recharger le cœur coupe
            // toutes les connexions en cours, téléchargements compris. Il faut
            // plusieurs échecs consécutifs avant d'engager une récupération.
            var consecutiveFailures = 0
            while (isActive && !userStopped) {
                // Sonde périodique (10 s) : assez fréquente pour détecter une
                // coupure réelle, assez espacée pour ne pas concurrencer le
                // trafic des applications.
                delay(HEALTH_PROBE_INTERVAL_MILLIS)
                if (!isActive || userStopped) break
                if (!isRunning()) {
                    // Ancien comportement : la boucle s'arrêtait ici, sans un mot.
                    // La mort du cœur est maintenant un événement explicite.
                    onUnexpectedCoreExit(service, "health_check_loop_exit", null, CoreOrigin.PROBE)
                    return@launch
                }
                val probe = try {
                    measureDelayWithFallback()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    -1L
                }
                val reachable = probe >= 0L
                val now = System.currentTimeMillis()
                // Fenêtre de qualité : une écriture toutes les 5 s suffit.
                if (now - lastHealthRecordAt >= HEALTH_RECORD_INTERVAL_MILLIS) {
                    lastHealthRecordAt = now
                    recordActiveHealth(probe)
                }
                // La sonde de santé traverse déjà le tunnel : on publie son
                // résultat tel quel, sans requête supplémentaire.
                MessageHelper.sendMsg2UI(
                    service,
                    AppConfig.MSG_SESSION_RTT,
                    SessionRttSample(probe, now, reachable),
                )
                if (!reachable) {
                    consecutiveFailures++
                } else {
                    consecutiveFailures = 0
                }
                val verdict = CoreRecoveryPolicy.probeVerdict(
                    reachable = reachable,
                    consecutiveFailures = consecutiveFailures,
                    failuresBeforeRecovery = HEALTH_FAILURES_BEFORE_RECOVERY,
                )
                if (verdict == ProbeVerdict.TUNNEL_SUSPECT) {
                    // Le tunnel reçoit encore des paquets : on le laisse vivre,
                    // seule une coupure confirmée justifie un arrêt.
                    LogUtil.w(
                        AppConfig.TAG,
                        "StartCore-Manager: probe failed ($consecutiveFailures/$HEALTH_FAILURES_BEFORE_RECOVERY), tunnel left untouched",
                    )
                    continue
                }
                if (verdict == ProbeVerdict.TUNNEL_DOWN) {
                    connectionVerified = false
                    supervisor.tunnelUnverified("probe_unreachable")
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_RECONNECTING, "")
                    NotificationManager.updateState(
                        service.getString(com.v2ray.ang.R.string.connection_reconnecting),
                        android.graphics.Color.rgb(245, 196, 81),
                    )
                    scheduleReconnect(service)
                    break
                }
                // Comparaison périodique avec les autres serveurs mesurés.
                if (now - lastSwitchEvaluationAt >= SWITCH_EVALUATION_INTERVAL_MILLIS) {
                    lastSwitchEvaluationAt = now
                    evaluateAutomaticSwitch(service)
                }
            }
        }
    }

    /**
     * Le relais TUN transporte-t-il réellement les paquets ?
     *
     * Une sonde qui réussit ne prouve que le trajet moteur → serveur : elle ne
     * dit rien du TUN. Sans ce contrôle, un relais mort laissait publier un
     * état connecté alors qu'aucune application ne pouvait transmettre.
     */
    private fun isTunnelPathAlive(): Boolean =
        runCatching { serviceControl?.isTunnelRelayRunning() ?: true }.getOrDefault(true)

    /**
     * Tests the configured endpoint and then the alternate endpoint when the first
     * request returns a negative delay.  A negative result is a failed probe, not
     * a reason to declare the VPN connected.
     */
    private fun measureDelayWithFallback(): Long {
        val primary = coreEngine.measureDelay(SettingsManager.getDelayTestUrl())
        if (primary >= 0L) return primary
        return coreEngine.measureDelay(SettingsManager.getDelayTestUrl(true))
    }

    /**
     * Point d'entrée unique de toutes les sources de récupération : sonde de
     * santé, `NetworkMonitor`, mort du cœur, redémarrage manuel.
     *
     * Le superviseur fusionne les demandes concurrentes, refuse une relance sur
     * un tunnel sain et applique le plafond anti-boucle. Aucune seconde opération
     * ne peut donc démarrer en parallèle.
     */
    private fun scheduleReconnect(
        service: Service,
        origin: CoreOrigin = CoreOrigin.PROBE,
        reason: String = "probe_failed",
    ) {
        if (fatalConfigError != null) return
        if (userStopped) return
        if (reconnectJob?.isActive == true) {
            // Une boucle tourne déjà : la demande est fusionnée, pas dupliquée.
            supervisor.noteRequestMerged(origin, "$reason:loop_active")
            return
        }
        when (val decision = supervisor.requestRecovery(origin, reason, coreRunning = isRunning())) {
            is RecoveryDecision.Start -> {
                reconnectJob = lifecycleScope.launch {
                    // Une exception qui s'échappe d'une coroutine détachée atteint
                    // le handler global du thread et tue le processus daemon
                    // (FATAL EXCEPTION). On la journalise et on ferme proprement.
                    try {
                        runReconnectLoop(decision)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "StartCore-Manager: reconnect loop aborted", e)
                        supervisor.recoveryFinished(false, "reconnect_loop_aborted", e)
                    }
                }
            }

            RecoveryDecision.Merged,
            RecoveryDecision.Healthy,
            RecoveryDecision.NotExpected,
            -> Unit

            is RecoveryDecision.LimitReached -> publishRecoveryLimitReached(service, decision)
        }
    }

    /**
     * Publie l'arrêt des relances automatiques.
     *
     * L'état est une erreur explicite : aucune tentative supplémentaire ne part
     * en boucle, et une connexion manuelle reste possible (elle ouvre un
     * nouvel épisode).
     */
    private fun publishRecoveryLimitReached(service: Service, decision: RecoveryDecision.LimitReached) {
        val windowMinutes = (decision.windowMillis / 60_000L).coerceAtLeast(1L)
        val message = service.getString(
            com.v2ray.ang.R.string.connection_recovery_stopped,
            decision.attempts,
            windowMinutes,
        )
        LogUtil.e(
            AppConfig.TAG,
            "StartCore-Manager: automatic recovery stopped after ${decision.attempts} attempts " +
                "in ${decision.windowMillis} ms",
        )
        MessageHelper.sendMsg2UI(
            service,
            AppConfig.MSG_STATE_START_FAILURE,
            message,
            null,
            "recovery_limit_reached",
        )
        NotificationManager.updateState(
            service.getString(com.v2ray.ang.R.string.connection_not_connected),
            android.graphics.Color.rgb(239, 68, 68),
        )
        XrayConsole.event("Core", "Automatic recovery stopped: attempt limit reached")
    }

    /**
     * Exécute les tentatives autorisées par le superviseur, une seule à la fois.
     *
     * Chaque tour de boucle consomme une tentative : le backoff vient de la
     * politique centralisée et le plafond s'applique aussi entre deux tentatives.
     */
    private suspend fun runReconnectLoop(initial: RecoveryDecision.Start) {
        var attempt = initial.attempt
        var backoffMillis = initial.backoffMillis
        var lastFailure: String? = null
        try {
            while (!userStopped && !connectionVerified && fatalConfigError == null) {
                val currentService = getService()
                if (currentService == null) {
                    lastFailure = "service_gone"
                    return
                }
                supervisor.recoveryAttemptStarted(attempt)
                MessageHelper.sendMsg2UI(currentService, AppConfig.MSG_STATE_RECONNECTING, "")
                NotificationManager.updateState(
                    currentService.getString(com.v2ray.ang.R.string.connection_reconnecting),
                    android.graphics.Color.rgb(245, 196, 81),
                )
                // Première tentative rapide ; le backoff augmente seulement si le
                // réseau reste réellement indisponible.
                if (backoffMillis > 0L) delay(backoffMillis)
                if (userStopped) return
                val previousVerification = connectionVerificationJob
                try {
                    when (CoreRecoveryPolicy.recoveryPath(
                        coreRunning = isRunning(),
                        relayRunning = isTunnelPathAlive(),
                    )) {
                        RecoveryPath.RELOAD_CORE -> {
                            // Borne chaque rechargement : un appel natif bloqué ne
                            // doit pas empêcher les tentatives suivantes.
                            kotlinx.coroutines.withTimeoutOrNull(RECONNECT_RELOAD_TIMEOUT_MILLIS) {
                                reloadCore(cancelConnectionChecks = false)
                            }
                        }

                        RecoveryPath.RESTART_SERVICE -> {
                            // Le cœur est mort : attendre la libération réelle avant
                            // de relancer, sinon deux instances se chevauchent.
                            val released = awaitCoreStopped(isCoreRunning = { isRunning() })
                            if (released) {
                                LauncherManager.startService(currentService)
                            } else {
                                lastFailure = "restart_stop_timeout"
                                supervisor.stopNotConfirmed(
                                    CoreOrigin.RECONNECT,
                                    "restart_stop_timeout",
                                    DEFAULT_CORE_STOP_TIMEOUT_MILLIS,
                                )
                            }
                        }

                        RecoveryPath.REBUILD_TUNNEL -> {
                            // Cœur vivant mais relais TUN mort : le trafic ne passe
                            // plus. Le service reconstruit interface et relais en
                            // arrêtant d'abord le cœur restant (jamais deux relais).
                            LogUtil.w(
                                AppConfig.TAG,
                                "StartCore-Manager: packet path is down while the core is alive, rebuilding the tunnel",
                            )
                            LauncherManager.startService(currentService)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Keep retrying after a transient launcher or core error.
                    lastFailure = "reconnect_error"
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: reconnect attempt failed", e)
                }
                // Wait only for the probe started by this attempt. The old loop
                // polled connectionVerified for up to 60 s after a failed probe,
                // preventing this still-active job from scheduling the next retry.
                if (!connectionVerified && !userStopped) {
                    awaitNextReconnectVerification(
                        previousJob = previousVerification,
                        currentJob = { connectionVerificationJob },
                    )
                }
                if (connectionVerified || userStopped) break
                supervisor.recoveryAttemptFailed(attempt, lastFailure ?: "verification_failed")
                val next = supervisor.beginFollowUpAttempt(
                    CoreOrigin.RECONNECT,
                    lastFailure ?: "verification_failed",
                    coreRunning = isRunning(),
                )
                if (next !is RecoveryDecision.Start) {
                    if (next is RecoveryDecision.LimitReached) {
                        lastFailure = "recovery_limit_reached"
                        publishRecoveryLimitReached(
                            getService() ?: currentService,
                            next,
                        )
                    }
                    break
                }
                attempt = next.attempt
                backoffMillis = next.backoffMillis
            }
        } finally {
            if (userStopped) {
                supervisor.recoveryFinished(false, "user_stopped")
            } else {
                supervisor.recoveryFinished(
                    connectionVerified,
                    if (connectionVerified) "tunnel_verified" else (lastFailure ?: "verification_failed"),
                )
            }
        }
    }

    /**
     * Bascule automatique : classement par qualité mesurée, changement de route
     * réel, retour au serveur précédent si le nouveau ne répond pas.
     *
     * Seuls les serveurs de Configs (groupe du profil actif, hors bibliothèque
     * publique) sont candidats : aucun serveur public n'est choisi ici.
     */
    private suspend fun tryFailover(service: Service): Boolean {
        val failed = currentConfig ?: return false
        val failedGuid = currentGuid ?: return false
        if (!isAutoFailoverEnabled()) return false
        if (LaboratoryTunnelBridge.read(service) != null) return false
        val groupId = failed.subscriptionId.ifBlank { AppConfig.DEFAULT_SUBSCRIPTION_ID }
        if (groupId.startsWith(PUBLIC_GROUP_PREFIX)) return false

        val candidates = MmkvManager.decodeServerList(groupId)
            .asSequence()
            .filter { it != failedGuid && it.isNotBlank() }
            .filter { guid ->
                MmkvManager.decodeServerConfig(guid)?.configType?.isGroupType() == false
            }
            .map { guid -> ServerHealthStore.snapshot(guid) }
            .filter { it.samples > 0 }
            .toList()
        if (candidates.isEmpty()) {
            LogUtil.i(AppConfig.TAG, "Failover: aucun candidat mesuré dans le groupe")
            return false
        }

        val decision = AutoFailoverPolicy.decide(
            active = ServerHealthStore.snapshot(failedGuid),
            candidates = candidates,
            nowMillis = System.currentTimeMillis(),
            lastSwitchAtMillis = MmkvManager.getLastFailoverAt(),
        )
        val candidate = decision.candidate
        if (candidate == null) {
            LogUtil.i(AppConfig.TAG, "Failover: pas de changement (${decision.reason})")
            return false
        }
        return applyFailover(service, failedGuid, candidate, decision.reason)
    }

    /** Applique la bascule, la vérifie pour de vrai, sinon revient en arrière. */
    private suspend fun applyFailover(
        service: Service,
        fromGuid: String,
        candidate: ServerHealthSnapshot,
        reason: String,
    ): Boolean {
        if (!failoverMutex.tryLock()) return false
        val fromName = currentConfig?.remarks.orEmpty()
        val target = MmkvManager.decodeServerConfig(candidate.guid)
        if (target == null) {
            failoverMutex.unlock()
            return false
        }
        LogUtil.w(
            AppConfig.TAG,
            "Failover: ${candidate.guid} (${reason}) score=${candidate.score} " +
                "latence=${candidate.latencyMillis}ms pertes=${candidate.lossPercent}%",
        )
        try {
            failoverAttempted = true
            MmkvManager.setSelectServer(candidate.guid)
            MmkvManager.setLastFailoverAt(System.currentTimeMillis())
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_RECONNECTING, "")
            NotificationManager.updateState(
                service.getString(com.v2ray.ang.R.string.connection_reconnecting),
                android.graphics.Color.rgb(245, 196, 81),
            )
            if (switchAndVerify(candidate.guid)) {
                ServerHealthStore.recordEvent(fromName, target.remarks, reason, "success")
                LogUtil.i(AppConfig.TAG, "Failover: trafic confirmé sur ${target.remarks}")
                return true
            }
            // Le candidat n'a pas donné de trafic réel : retour explicite.
            MmkvManager.setSelectServer(fromGuid)
            switchAndVerify(fromGuid)
            ServerHealthStore.recordEvent(fromName, target.remarks, reason, "rollback")
            LogUtil.w(AppConfig.TAG, "Failover: candidat rejeté, retour au serveur précédent")
            return false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failover: échec de la bascule", e)
            runCatching { MmkvManager.setSelectServer(fromGuid) }
            return false
        } finally {
            failoverMutex.unlock()
        }
    }

    /**
     * Recharge le cœur avec [expectedGuid] et attend la vérification réelle du
     * trafic : un simple redémarrage du cœur ne prouve rien.
     */
    private suspend fun switchAndVerify(expectedGuid: String): Boolean {
        if (!isRunning()) return false
        val reloaded = withTimeoutOrNull(FAILOVER_RELOAD_TIMEOUT_MILLIS) {
            reloadCore(cancelConnectionChecks = false)
        } == true
        if (!reloaded) return false
        var waited = 0L
        while (waited < FAILOVER_VERIFY_WAIT_MILLIS) {
            if (connectionVerified && isRunning() && currentGuid == expectedGuid) return true
            if (!isRunning()) return false
            delay(250L)
            waited += 250L
        }
        return false
    }

    /** Enregistre une mesure réelle du serveur actif (sonde de santé). */
    private fun recordActiveHealth(probe: Long) {
        val guid = currentGuid ?: return
        val protocol = currentConfig?.configType?.name.orEmpty()
        if (probe >= 0L) {
            ServerHealthStore.recordSuccess(guid, protocol, probe)
        } else {
            ServerHealthStore.recordFailure(guid, protocol)
        }
    }

    private fun isAutoFailoverEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SWITCH_DEFAULT_SERVER, true)

    /** Compare le serveur actif aux autres : aucune requête réseau en plus. */
    private fun evaluateAutomaticSwitch(service: Service) {
        if (failoverJob?.isActive == true) return
        if (!isAutoFailoverEnabled()) return
        if (LaboratoryTunnelBridge.read(service) != null) return
        failoverJob = connectionTestScope.launch {
            runCatching { tryFailover(service) }
        }
    }

    private fun startTrafficStats(service: Service) {
        trafficStatsJob?.cancel()
        trafficStatsDiagnosticRead = 0
        trafficStatsJob = connectionTestScope.launch {
            var previousTime = System.currentTimeMillis()
            while (isRunning()) {
                delay(1000L)
                val now = System.currentTimeMillis()
                val diagnosticEnabled = BuildConfig.DEBUG || MmkvManager.decodeSettingsBool(
                    AppConfig.PREF_DIAGNOSTIC_TRAFFIC_STATS,
                    false,
                )
                val diagnosticRead = if (diagnosticEnabled && trafficStatsDiagnosticRead < 3) {
                    ++trafficStatsDiagnosticRead
                } else {
                    null
                }
                val stats = queryAllOutboundTrafficStats(diagnosticRead)
                // QueryAllOutboundTrafficStats reads and resets Xray's counters.
                // Device-wide counters include unrelated apps and must not be
                // presented as tunnel traffic.
                val snapshot = trafficSnapshotFromStats(
                    stats = stats,
                    elapsedMillis = now - previousTime,
                    previous = _trafficSnapshot.value,
                ) ?: TrafficSnapshot(
                    uplinkTotalBytes = _trafficSnapshot.value.uplinkTotalBytes,
                    downlinkTotalBytes = _trafficSnapshot.value.downlinkTotalBytes,
                    available = false,
                )
                val dailyMobileUsage = if (snapshot.available && networkMonitor?.underlyingIsCellular == true) {
                    val uplink = stats.filter { it.direction == AppConfig.UPLINK }.sumOf { it.value.coerceAtLeast(0L) }
                    val downlink = stats.filter { it.direction == AppConfig.DOWNLINK }.sumOf { it.value.coerceAtLeast(0L) }
                    MobileTrafficUsageStore.add(uplink, downlink)
                } else {
                    MobileTrafficUsageStore.current()
                }
                val snapshotWithDailyMobileUsage = snapshot.copy(
                    mobileDailyUplinkBytes = dailyMobileUsage.uplinkBytes,
                    mobileDailyDownlinkBytes = dailyMobileUsage.downlinkBytes,
                )
                previousTime = now
                _trafficSnapshot.value = snapshotWithDailyMobileUsage
                NotificationManager.updateTraffic(snapshotWithDailyMobileUsage)
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_TRAFFIC_UPDATE, snapshotWithDailyMobileUsage)
            }
        }
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    suspend fun stopCoreLoop(
        cancelConnectionChecks: Boolean = true,
        origin: CoreOrigin = CoreOrigin.USER,
        onCoreStopped: suspend () -> Unit = {},
    ): Boolean = lifecycleGate.stop {
        connectionVerified = false
        // Arrêt demandé : une sortie du moteur pendant cette fenêtre est
        // attendue et ne doit pas être requalifiée en incident.
        supervisor.stopRequested(origin)
        coreWatchdogJob?.cancel()
        coreWatchdogJob = null
        if (cancelConnectionChecks) connectionTestScope.coroutineContext.cancelChildren()
        healthCheckJob?.cancel()
        healthCheckJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        fatalConfigError = null
        connectionVerificationJob?.cancel()
        connectionVerificationJob = null
        delayMeasurementJob?.cancel()
        delayMeasurementJob = null
        trafficStatsJob?.cancel()
        trafficStatsJob = null
        _trafficSnapshot.value = TrafficSnapshot()
        val service = getService()
        if (service == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: stop requested without a service instance")
            return@stop false
        }
        val laboratory = LaboratoryTunnelBridge.isActive(service)
        val mode = if (laboratory) {
            LaboratoryTunnelBridge.MODE_LABORATORY
        } else {
            LaboratoryTunnelBridge.MODE_CLASSIC
        }
        LaboratoryTunnelBridge.writeStatus(service, LaboratoryTunnelBridge.STATE_STOPPING, mode)

        networkMonitor?.unregister()
        networkMonitor = null
        if (isRunning()) {
            val stopped = try {
                // Native stopLoop is blocking; isolate it from Android's service thread.
                // The lifecycle gate holds start/restart until this call actually returns.
                withTimeoutOrNull(NATIVE_STOP_TIMEOUT_MILLIS) {
                    withContext(Dispatchers.IO) { coreEngine.stop() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
                publishStopFailure(service, mode, "native_stop_error")
                return@stop false
            }
            if (stopped == null) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: native stopLoop timed out")
                publishStopFailure(service, mode, "native_stop_timeout")
                return@stop false
            }
        }

        // Le drapeau natif peut se mettre à jour de façon asynchrone : on lui
        // laisse une courte grâce avant de conclure, plutôt que de lever une
        // exception qui laissait le service à moitié arrêté et sans message.
        var waitMillis = 0L
        while (isRunning() && waitMillis < CORE_DOWN_GRACE_MILLIS) {
            delay(50L)
            waitMillis += 50L
        }
        if (isRunning()) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Core loop still running after stopLoop returned")
            publishStopFailure(service, mode, "core_still_running")
            return@stop false
        }
        currentVpnInterface = null
        currentOutboundTags = emptySet()
        // Un arrêt du tunnel termine toujours la session laboratoire : elle ne
        // doit jamais être reprise par une connexion classique ultérieure.
        LaboratoryTunnelBridge.clear(service)
        LaboratoryTunnelBridge.writeStatus(service, LaboratoryTunnelBridge.STATE_STOPPED, mode)
        onCoreStopped()

        // Close existing browser dialer
        CoreNativeManager.reconcileBrowserDialer("")
        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }

        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        XrayConsole.event("Core", "Tunnel stopped")
        ConnectionFeedback.onDisconnected(service)
        NotificationManager.cancelNotification()
        supervisor.stopped(origin)
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Tunnel stopped and resources released")
        true
    }

    /**
     * Redémarrage manuel : arrêt réel confirmé, puis une seule relance.
     *
     * Le délai maximal et l'interdiction de lancer une seconde instance vivent
     * dans `performManualRestart`, partagé avec les tests.
     */
    private suspend fun restartTunnel(service: Service) {
        val mode = if (LaboratoryTunnelBridge.isActive(service)) {
            LaboratoryTunnelBridge.MODE_LABORATORY
        } else {
            LaboratoryTunnelBridge.MODE_CLASSIC
        }
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: restart requested, stopping the core first")
        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_RECONNECTING, "")
        NotificationManager.updateState(
            service.getString(com.v2ray.ang.R.string.connection_reconnecting),
            android.graphics.Color.rgb(245, 196, 81),
        )

        val outcome = performManualRestart(
            supervisor = supervisor,
            origin = CoreOrigin.NOTIFICATION,
            isCoreRunning = { isRunning() },
            requestStop = { serviceControl?.stopService() },
            requestStart = { LauncherManager.startService(service) },
        )

        when (outcome) {
            RestartOutcome.STARTED -> LogUtil.i(
                AppConfig.TAG,
                "StartCore-Manager: core stopped and a single new instance was requested",
            )

            RestartOutcome.STOP_NOT_CONFIRMED -> {
                LogUtil.e(
                    AppConfig.TAG,
                    "StartCore-Manager: restart aborted, core stop not confirmed within " +
                        "${DEFAULT_CORE_STOP_TIMEOUT_MILLIS} ms",
                )
                publishStopFailure(service, mode, "restart_stop_timeout")
            }

            RestartOutcome.STOP_REQUEST_FAILED -> {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: restart aborted, stop request failed")
                publishStopFailure(service, mode, "restart_request_failed")
            }

            RestartOutcome.START_REQUEST_FAILED -> {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: restart aborted, start request failed")
                publishStopFailure(service, mode, "restart_request_failed")
            }
        }
    }

    /**
     * Publie un échec d'arrêt avec un motif exploitable.
     *
     * Aucun état « arrêté » n'est annoncé : le TUN et le moteur peuvent encore
     * être actifs. L'interface doit proposer une nouvelle tentative.
     */
    private fun publishStopFailure(service: Service, mode: String, reason: String) {
        // L'arrêt a échoué : la surveillance doit reprendre si le moteur tourne
        // encore, sinon une mort ultérieure passerait inaperçue.
        supervisor.stopFailed(reason)
        if (isRunning()) startCoreWatchdog(service)
        LaboratoryTunnelBridge.writeStatus(
            service,
            LaboratoryTunnelBridge.STATE_STOP_FAILED,
            mode,
            reason,
        )
        MessageHelper.sendMsg2UI(
            service,
            AppConfig.MSG_STATE_STOP_FAILURE,
            reason,
            null,
            reason,
        )
    }

    /**
     * Subscribes to upstream network changes for whichever run mode is active.
     * All three services share this manager, so the tunnel recovers from a handover in proxy only
     * and root mode as well, not just behind the VPN interface.
     */
    private fun startNetworkMonitor(service: Service) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (networkMonitor != null) return

        val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkMonitor = NetworkMonitor(
            connectivity = connectivity,
            onUnderlyingNetworksChanged = { networks -> serviceControl?.setUnderlyingNetworks(networks) },
            onHandover = { onNetworkHandover() },
        ).also { it.register() }
    }

    /**
     * Changement de réseau : vérifier la connectivité réelle avant de décider.
     *
     * Un tunnel encore vérifié après le handover ne doit pas être redémarré : le
     * rechargement ne part que si la sonde échoue ou si le cœur est déjà mort.
     * Toutes les demandes passent par le superviseur, qui les fusionne.
     */
    private suspend fun onNetworkHandover() {
        if (userStopped) return
        // Une récupération est déjà en cours : elle couvre ce changement.
        if (supervisor.isRecoveryInFlight()) return
        val service = getService() ?: return

        if (!isRunning()) {
            scheduleReconnect(service, CoreOrigin.NETWORK, "core_down_after_handover")
            return
        }

        // Sonde courte : au-delà, la socket est considérée morte et le tunnel
        // est rechargé plutôt que de laisser l'utilisateur sans trafic.
        val stillVerified = withTimeoutOrNull(HANDOVER_VERIFY_TIMEOUT_MILLIS) {
            runCatching { measureDelayWithFallback() }.getOrDefault(-1L) >= 0L
        } ?: false
        if (stillVerified) {
            supervisor.tunnelVerified()
            XrayConsole.event("Network", "Upstream changed, tunnel still verified: core left untouched")
            LogUtil.i(AppConfig.TAG, "NetworkMonitor: tunnel still verified after handover")
            return
        }
        XrayConsole.event("Network", "Upstream changed, tunnel unverified: recovery requested")
        scheduleReconnect(service, CoreOrigin.NETWORK, "handover_unverified")
    }

    /**
     * Restarts the core in place after the upstream network changed: the service, the notification
     * and the VPN interface all stay up, so nothing of this is visible.
     *
     * The config is rebuilt on purpose, outbound server domains are resolved while building it and
     * an address resolved on a network that is gone can be unusable on the new one.
     *
     * @return True if the core is running again.
     */
    private suspend fun reloadCore(cancelConnectionChecks: Boolean = true): Boolean = lifecycleGate.start {
        if (isReloading) return@start false
        val service = getService() ?: return@start false
        if (!isRunning()) return@start false

        try {
            val tunFd = currentVpnInterface

            isReloading = true
            connectionVerified = false
            // Toujours annuler les sondes en vol *avant* stopLoop() : une sonde qui
            // survit à l'arrêt du core échoue sur "io: read/write on closed pipe".
            connectionVerificationJob?.cancel()
            connectionVerificationJob = null
            delayMeasurementJob?.cancel()
            delayMeasurementJob = null
            if (cancelConnectionChecks) {
                healthCheckJob?.cancel()
                healthCheckJob = null
                trafficStatsJob?.cancel()
                trafficStatsJob = null
            }
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload start...")
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_RECONNECTING, "")
            NotificationManager.updateState(service.getString(com.v2ray.ang.R.string.connection_reconnecting), android.graphics.Color.rgb(245, 196, 81))

            // Rechargement volontaire : la sortie de l'ancienne boucle est
            // attendue, elle ne doit pas déclencher une récupération en boucle.
            supervisor.stopRequested(CoreOrigin.RECONNECT)
            withContext(Dispatchers.IO) { coreEngine.stop() }
            launchCore(service, tunFd, isReload = true, origin = CoreOrigin.RECONNECT)

            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload finished")
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: ConfigValidationException) {
            // Configuration invalide : le moteur échouerait à chaque tentative.
            // On arrête proprement et on remonte le motif au lieu de boucler.
            val message = e.message?.takeUnless { it.isBlank() } ?: "Configuration invalide"
            fatalConfigError = message
            // L'état ne doit pas rester « arrêt demandé » : le moteur tourne
            // peut-être encore et sa surveillance doit rester active.
            supervisor.stopFailed("config_invalid")
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: invalid configuration: $message")
            MessageHelper.sendMsg2UI(
                service,
                AppConfig.MSG_STATE_START_FAILURE,
                message,
                null,
                "config_invalid",
            )
            NotificationManager.cancelNotification()
            false
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to reload core: $message", e)
            supervisor.stopFailed("reload_failed")
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_RECONNECTING, "")
            if (!userStopped) scheduleReconnect(service, CoreOrigin.RECONNECT, "reload_failed")
            false
        } finally {
            isReloading = false
        }
    }

    /**
     * Lit et remet à zéro les compteurs via `queryAllOutboundTrafficStats()`
     * de l'AAR v26.11.9 (une seule traversée JNI par lecture, au lieu de deux
     * par tag).
     *
     * L'API native ne renvoie que les compteurs non nuls : les tags suivis
     * absents de la réponse sont réémis à zéro, afin qu'une absence de trafic
     * reste une mesure valide et non une indisponibilité.
     */
    fun queryAllOutboundTrafficStats(diagnosticRead: Int? = null): List<OutboundTrafficStat> {
        if (!isRunning() || currentOutboundTags.isEmpty()) return emptyList()
        return try {
            val tags = currentOutboundTags + AppConfig.TAG_DIRECT
            val counters = coreEngine.readOutboundTrafficStats()
                .associateBy { it.tag to it.direction }
            val values = tags.flatMap { tag ->
                listOf(
                    OutboundTrafficStat(tag, AppConfig.UPLINK, counters[tag to AppConfig.UPLINK]?.value ?: 0L),
                    OutboundTrafficStat(tag, AppConfig.DOWNLINK, counters[tag to AppConfig.DOWNLINK]?.value ?: 0L),
                )
            }
            if (BuildConfig.DEBUG && diagnosticRead != null) {
                Log.d(
                    TRAFFIC_STATS_DIAGNOSTIC_TAG,
                    "read=$diagnosticRead queriedTags=${tags.joinToString()} raw=${values.joinToString { "${it.tag}/${it.direction}=${it.value}" }}",
                )
            }
            values
        } catch (error: Exception) {
            if (BuildConfig.DEBUG && diagnosticRead != null) {
                // N'écrit que le type d'exception : le message natif peut contenir des données sensibles.
                Log.d(TRAFFIC_STATS_DIAGNOSTIC_TAG, "read=$diagnosticRead query failed: ${error.javaClass.simpleName}")
            }
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: failed to query traffic statistics", error)
            emptyList()
        }
    }

    private const val TRAFFIC_STATS_DIAGNOSTIC_TAG = "DamsTrafficStats"

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    private fun measureV2rayDelay(requestId: String) {
        val service = getService() ?: return
        if (!isRunning() || isReloading) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
            return
        }

        delayMeasurementJob?.cancel()
        val measurementJob = connectionTestScope.launch {
            var time = -1L
            var errorStr = ""

            try {
                time = coreEngine.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                errorStr = e.message?.substringAfter("\":").orEmpty()
            }
            if (time == -1L) {
                ensureActive()
                try {
                    time = coreEngine.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorStr = e.message?.substringAfter("\":").orEmpty()
                }
            }

            ensureActive()
            val endpoint = if (time >= 0) SpeedtestManager.getRemoteIPInfo() else null
            val result = ConnectionTestResult(
                delayMillis = time,
                errorMessage = errorStr,
                country = endpoint?.country,
                ipAddress = endpoint?.ipAddress,
            )
            withContext(Dispatchers.Main.immediate) {
                if (isRunning()) {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, result, requestId)
                } else {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
                }
            }
        }
        delayMeasurementJob = measurementJob
        measurementJob.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
            }
        }
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback startup")
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         *
         * L'AAR v26.11.9 appelle ce rappel depuis `StopLoop()` ; les versions
         * antérieures ne l'appelaient jamais. Il ne sert qu'à journaliser : la
         * sortie de la boucle reste détectée par la scrutation du drapeau natif
         * et par la sonde réelle. Le corps est protégé, un rappel qui lève
         * traverserait la frontière native et tuerait le processus.
         */
        override fun shutdown(): Long {
            return try {
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback shutdown")
                0L
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: CoreCallback shutdown failed", e)
                0L
            }
        }

        /**
         * Called when V2Ray core emits status information.
         *
         * Le moteur émet « Core stopped » depuis `StopLoop()` et
         * « Started successfully, running » depuis un démarrage réussi. Un
         * « Core stopped » qui n'a pas été demandé est une sortie spontanée.
         *
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            return try {
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback onEmitStatus $s")
                val stopped = s?.contains("Core stopped", ignoreCase = true) == true
                if (stopped && !supervisor.isStopRequested() && supervisor.isExpectingRunning()) {
                    val service = getService()
                    if (service != null) {
                        onUnexpectedCoreExit(service, "native_status_core_stopped", null, CoreOrigin.WATCHDOG)
                    }
                }
                0L
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: CoreCallback onEmitStatus failed", e)
                0L
            }
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val control = serviceControl ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    // Accusé de réception : permet à l'interface de distinguer
                    // « démon absent » de « démon présent, sans état publié ».
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                    if (isRunning()) {
                        MessageHelper.sendMsg2UI(
                            control.getService(),
                            if (connectionVerified) AppConfig.MSG_STATE_START_SUCCESS else AppConfig.MSG_STATE_CONNECTING,
                            "",
                        )
                    } else {
                        MessageHelper.sendMsg2UI(control.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    // Accusé de réception immédiat : l'appelant sait que le
                    // démon a bien reçu la demande et qu'il doit attendre un
                    // état final (STOP_SUCCESS ou STOP_FAILURE).
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                    control.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    // The UI and daemon run in separate processes, so acknowledge the active
                    // daemon before stopping it instead of relying on possibly stale UI state.
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK

                    val pendingResult = goAsync()
                    lifecycleScope.launch {
                        try {
                            restartTunnel(control.getService())
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            LogUtil.e(AppConfig.TAG, "StartCore-Manager: manual restart failed", e)
                            supervisor.recoveryFinished(false, "manual_restart_error", e)
                            runCatching {
                                MessageHelper.sendMsg2UI(
                                    control.getService(),
                                    AppConfig.MSG_STATE_STOP_FAILURE,
                                    "manual_restart_error",
                                    null,
                                    "manual_restart_error",
                                )
                            }
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                    measureV2rayDelay(intent.getStringExtra("content").orEmpty())
                }

                AppConfig.MSG_LAB_RELOAD -> {
                    // Rechargement demandé par le laboratoire après un changement
                    // de sélection de chemins. Ignoré si le labo n'est pas actif.
                    val currentService = control.getService()
                    if (LaboratoryTunnelBridge.read(currentService) != null) {
                        if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                        val pendingResult = goAsync()
                        connectionTestScope.launch {
                            try {
                                kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                                    reloadCore(cancelConnectionChecks = false)
                                }
                            } finally {
                                pendingResult.finish()
                            }
                        }
                    }
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification()
                }

                Intent.ACTION_SCREEN_ON -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    NotificationManager.startSpeedNotification()
                }
            }
        }
    }
}

// ── Bascule automatique du VPN classique ─────────────────────────────────────

/** Les serveurs de la bibliothèque publique restent hors du choix automatique. */
private const val PUBLIC_GROUP_PREFIX = "dams-public-"

/** Écriture de la fenêtre de qualité (la sonde, elle, reste à 1 s). */
private const val HEALTH_RECORD_INTERVAL_MILLIS = 5_000L

/** Fréquence de la sonde de santé à travers le tunnel. */
private const val HEALTH_PROBE_INTERVAL_MILLIS = 10_000L

/**
 * Nombre d'échecs consécutifs avant d'engager une récupération.
 *
 * Une sonde perdue (congestion, résolveur lent) ne doit jamais faire recharger
 * le cœur : un rechargement coupe toutes les connexions en cours.
 */
private const val HEALTH_FAILURES_BEFORE_RECOVERY = 2

/** Fréquence de surveillance de la durée de vie réelle de la boucle native. */
private const val WATCHDOG_INTERVAL_MILLIS = 1_000L

/** Borne d'un rechargement pendant une relance : un appel natif bloqué ne gèle pas la boucle. */
private const val RECONNECT_RELOAD_TIMEOUT_MILLIS = 12_000L

/**
 * Sonde courte après un changement de réseau : au-delà, la socket est
 * considérée morte et le cœur est rechargé.
 */
private const val HANDOVER_VERIFY_TIMEOUT_MILLIS = 3_000L

/** Comparaison périodique du serveur actif avec les autres Configs. */
private const val SWITCH_EVALUATION_INTERVAL_MILLIS = 60_000L

/** Rechargement borné avant de conclure que le candidat ne démarre pas. */
private const val FAILOVER_RELOAD_TIMEOUT_MILLIS = 20_000L

/** Attente de la vérification réelle du trafic après bascule. */
private const val FAILOVER_VERIFY_WAIT_MILLIS = 12_000L
