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
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.OutboundTrafficStat
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
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
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
import kotlinx.coroutines.isActive
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import java.lang.ref.SoftReference

object CoreServiceManager {

    private val lifecycleGate = CoreLoopLifecycleGate()

    private val coreController: CoreController = CoreNativeManager.newCoreController(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    private var currentOutboundTags: Set<String> = emptySet()
    private var browserDialer: IDialerService? = null
    private var networkMonitor: NetworkMonitor? = null
    private val connectionTestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _trafficSnapshot = MutableStateFlow(TrafficSnapshot())
    val trafficSnapshot: StateFlow<TrafficSnapshot> = _trafficSnapshot.asStateFlow()
    private var trafficStatsJob: kotlinx.coroutines.Job? = null
    private var trafficStatsDiagnosticRead = 0
    private var connectionVerificationJob: kotlinx.coroutines.Job? = null
    private var delayMeasurementJob: kotlinx.coroutines.Job? = null
    private var healthCheckJob: kotlinx.coroutines.Job? = null
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var reconnectAttempt = 0

    @Volatile
    private var isReloading = false
    private var failoverAttempted = false

    @Volatile
    private var userStopped = true

    @Volatile
    private var connectionVerified = false

    /** Appelé uniquement par une action explicite de l'utilisateur. */
    fun markUserStopped() {
        userStopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        healthCheckJob?.cancel()
        healthCheckJob = null
        reconnectAttempt = 0
    }

    /** Tun descriptor the core was started with, null in the proxy only and root run modes. */
    private var currentVpnInterface: ParcelFileDescriptor? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            val service = value?.get()?.getService()
            CoreNativeManager.initCoreEnv(service)
        }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = coreController.isRunning

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
    suspend fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean = lifecycleGate.start {
        if (isRunning()) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return@start false
        }

        val service = getService()
        if (service == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return@start false
        }

        try {
            userStopped = false
            failoverAttempted = false
            connectionVerified = false
            doStartCoreLoop(service, vpnInterface)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            NotificationManager.cancelNotification()
            false
        }
    }

    @Throws(Exception::class)
    private fun doStartCoreLoop(service: Service, vpnInterface: ParcelFileDescriptor?) {
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
        mFilter.addAction(Intent.ACTION_SCREEN_ON)
        mFilter.addAction(Intent.ACTION_SCREEN_OFF)
        mFilter.addAction(Intent.ACTION_USER_PRESENT)
        ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())

        currentVpnInterface = vpnInterface
        launchCore(service, vpnInterface)
        startNetworkMonitor(service)
    }

    @Throws(Exception::class)
    private fun launchCore(service: Service, vpnInterface: ParcelFileDescriptor?, isReload: Boolean = false) {
        val guid = MmkvManager.getSelectServer() ?: error("No server selected")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")

        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for profile $guid")
        val result = CoreConfigManager.getV2rayConfig(service, guid)
        LogUtil.d(AppConfig.TAG, "Generated Xray configuration for profile $guid")
        if (!result.status) {
            error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }

        currentOutboundTags = outboundTagsFromConfig(result.content)

        currentConfig = config
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
        coreController.startLoop(result.content, tunFd)

        if (!isRunning()) {
            error("Core failed to start")
        }

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
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
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
            if (delayMillis >= 0 && isRunning()) {
                connectionVerified = true
                reconnectAttempt = 0
                startHealthCheck(service)
                NotificationManager.updateState(service.getString(com.v2ray.ang.R.string.connection_connected), android.graphics.Color.rgb(34, 211, 238))
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
            } else if (isRunning()) {
                connectionVerified = false
                if (!failoverAttempted && tryFailover(service)) {
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
        healthCheckJob = connectionTestScope.launch {
            while (isActive && !userStopped && isRunning()) {
                // Vérification courte : une coupure doit déclencher la reconnexion
                // avant que l'utilisateur ne perçoive un tunnel abandonné.
                // Détecter une coupure rapidement sans attendre deux secondes complètes.
                delay(1_000L)
                if (!isRunning() || userStopped) break
                val reachable = try {
                    measureDelayWithFallback() >= 0L
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (!reachable) {
                    connectionVerified = false
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_RECONNECTING, "")
                    NotificationManager.updateState(
                        service.getString(com.v2ray.ang.R.string.connection_reconnecting),
                        android.graphics.Color.rgb(245, 196, 81),
                    )
                    scheduleReconnect(service)
                    break
                }
            }
        }
    }

    /**
     * Tests the configured endpoint and then the alternate endpoint when the first
     * request returns a negative delay.  A negative result is a failed probe, not
     * a reason to declare the VPN connected.
     */
    private fun measureDelayWithFallback(): Long {
        val primary = coreController.measureDelay(SettingsManager.getDelayTestUrl())
        if (primary >= 0L) return primary
        return coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
    }

    private fun scheduleReconnect(service: Service) {
        if (userStopped || reconnectJob?.isActive == true) return
        reconnectJob = connectionTestScope.launch {
            // Première tentative rapide ; le backoff augmente seulement si le réseau
            // reste réellement indisponible.
            val backoff = longArrayOf(1_000L, 3_000L, 8_000L, 20_000L)
            while (isActive && !userStopped && !connectionVerified) {
                val wait = backoff[reconnectAttempt.coerceAtMost(backoff.lastIndex)]
                reconnectAttempt++
                delay(wait)
                if (userStopped) return@launch
                val currentService = getService() ?: return@launch
                MessageHelper.sendMsg2UI(currentService, AppConfig.MSG_STATE_RECONNECTING, "")
                NotificationManager.updateState(
                    currentService.getString(com.v2ray.ang.R.string.connection_reconnecting),
                    android.graphics.Color.rgb(245, 196, 81),
                )
                val previousVerification = connectionVerificationJob
                try {
                    if (isRunning()) {
                        // Borne chaque rechargement : un appel natif bloqué ne doit
                        // pas empêcher les tentatives suivantes.
                        kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                            reloadCore(cancelConnectionChecks = false)
                        }
                    } else {
                        LauncherManager.startService(currentService)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep retrying after a transient launcher or core error.
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
            }
        }
    }

    /** Essaie une autre configuration du groupe avant d'abandonner la connexion. */
    private suspend fun tryFailover(service: Service): Boolean {
        val failed = currentConfig ?: return false
        val automaticFailoverEnabled = MmkvManager.decodeSettingsBool(
            AppConfig.PREF_AUTO_SWITCH_DEFAULT_SERVER,
            false,
        )
        if (!shouldTryDefaultServerFailover(
                automaticFailoverEnabled,
                failed.subscriptionId,
                AppConfig.DEFAULT_SUBSCRIPTION_ID,
            )
        ) return false
        val candidates = MmkvManager.decodeServerList(failed.subscriptionId)
            .asSequence()
            .filter { it != MmkvManager.getSelectServer() }
            .mapNotNull { guid -> MmkvManager.decodeServerConfig(guid)?.let { guid to it } }
            .filter { (_, profile) -> !profile.configType.isGroupType() }
            .toList()
        val next = candidates.firstOrNull() ?: return false

        failoverAttempted = true
        MmkvManager.setSelectServer(next.first)
        LogUtil.w(AppConfig.TAG, "StartCore-Manager: switching to backup profile")
        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_RECONNECTING, "")
        NotificationManager.updateState(
            service.getString(com.v2ray.ang.R.string.connection_reconnecting),
            android.graphics.Color.rgb(245, 196, 81),
        )
        return reloadCore(cancelConnectionChecks = false)
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
        onCoreStopped: suspend () -> Unit = {},
    ): Boolean = lifecycleGate.stop {
        connectionVerified = false
        if (cancelConnectionChecks) connectionTestScope.coroutineContext.cancelChildren()
        healthCheckJob?.cancel()
        healthCheckJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        connectionVerificationJob?.cancel()
        connectionVerificationJob = null
        delayMeasurementJob?.cancel()
        delayMeasurementJob = null
        trafficStatsJob?.cancel()
        trafficStatsJob = null
        _trafficSnapshot.value = TrafficSnapshot()
        val service = getService() ?: return@stop false

        networkMonitor?.unregister()
        networkMonitor = null
        if (isRunning()) {
            try {
                // Native stopLoop is blocking; isolate it from Android's service thread.
                // The lifecycle gate holds start/restart until this call actually returns.
                withContext(Dispatchers.IO) { coreController.stopLoop() }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
                throw e
            }
        }

        if (isRunning()) error("Core loop still running after stopLoop returned")
        currentVpnInterface = null
        currentOutboundTags = emptySet()
        onCoreStopped()

        // Close existing browser dialer
        CoreNativeManager.reconcileBrowserDialer("")
        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }

        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        NotificationManager.cancelNotification()

        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
        }
        true
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
            onUnderlyingNetworksChanged = { networks -> serviceControl?.get()?.setUnderlyingNetworks(networks) },
            onHandover = { reloadCore() },
        ).also { it.register() }
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
            if (cancelConnectionChecks) {
                connectionVerificationJob?.cancel()
                delayMeasurementJob?.cancel()
                healthCheckJob?.cancel()
                trafficStatsJob?.cancel()
            }
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload start...")
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_RECONNECTING, "")
            NotificationManager.updateState(service.getString(com.v2ray.ang.R.string.connection_reconnecting), android.graphics.Color.rgb(245, 196, 81))

            withContext(Dispatchers.IO) { coreController.stopLoop() }
            launchCore(service, tunFd, isReload = true)

            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload finished")
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to reload core: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_RECONNECTING, "")
            if (!userStopped) scheduleReconnect(service)
            false
        } finally {
            isReloading = false
        }
    }

    /**
     * Reads and resets counters through the stable API exported by the pinned
     * libv2ray AAR. The newer QueryAll helper is not present in the CI artifact.
     */
    fun queryAllOutboundTrafficStats(diagnosticRead: Int? = null): List<OutboundTrafficStat> {
        if (!isRunning()) return emptyList()
        return try {
            val tags = currentOutboundTags + AppConfig.TAG_DIRECT
            if (currentOutboundTags.isEmpty()) return emptyList()
            val values = mutableListOf<OutboundTrafficStat>()
            tags.forEach { tag ->
                values += OutboundTrafficStat(tag, AppConfig.UPLINK, coreController.queryStats(tag, AppConfig.UPLINK))
                values += OutboundTrafficStat(tag, AppConfig.DOWNLINK, coreController.queryStats(tag, AppConfig.DOWNLINK))
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
                time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                errorStr = e.message?.substringAfter("\":").orEmpty()
            }
            if (time == -1L) {
                ensureActive()
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
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
        return serviceControl?.get()?.getService()
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
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback shutdown")
            return 0
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback onEmitStatus $s")
            return 0
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
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (isRunning()) {
                        MessageHelper.sendMsg2UI(
                            serviceControl.getService(),
                            if (connectionVerified) AppConfig.MSG_STATE_START_SUCCESS else AppConfig.MSG_STATE_CONNECTING,
                            "",
                        )
                    } else {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
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
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    // The UI and daemon run in separate processes, so acknowledge the active
                    // daemon before stopping it instead of relying on possibly stale UI state.
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK

                    val pendingResult = goAsync()
                    connectionTestScope.launch {
                        try {
                            serviceControl.stopService()
                            delay(500L)
                            LauncherManager.startService(serviceControl.getService())
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                    measureV2rayDelay(intent.getStringExtra("content").orEmpty())
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
