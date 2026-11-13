package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Network
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.StrictMode
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.root.RootLanSharing
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("VpnServicePolicy")
class CoreVpnService : VpnService(), ServiceControl {
    private lateinit var mInterface: ParcelFileDescriptor
    private var isRunning = false
    private var tun2SocksService: Tun2SocksControl? = null
    private val isStartingLock = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null
    private var stopJob: Job? = null
    private var vpnWakeLockLease: VpnWakeLockLease? = null

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service created")
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        // Référence forte : le contrôle doit rester utilisable pendant toute la
        // vie du service, notamment pour garantir startForeground() à chaque
        // démarrage et traiter les demandes d'arrêt.
        CoreServiceManager.serviceControl = this
        // Branché ici et non au démarrage du cœur : un arrêt ou une demande
        // d'état reçoit toujours une réponse, même si Xray n'a pas démarré.
        CoreServiceManager.attachServiceReceiver(this)
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        stopAllService()
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")
        super.onDestroy()
        unlockStart()
        CoreServiceManager.detachServiceReceiver(this)
        serviceScope.launch {
            stopJob?.join()
            if (CoreServiceManager.isRunning()) performStop(closeInterface = true, stopService = false)
            if (!CoreServiceManager.isRunning()) {
                releaseVpnWakeLock()
                NotificationManager.cancelNotification()
            }
            // Libération forte de la référence : seulement après la séquence
            // d'arrêt, qui publie ses états finaux via ce contrôle. Si un
            // nouveau service s'est déjà rattaché, la libération est ignorée.
            CoreServiceManager.clearServiceControl(this@CoreVpnService)
            serviceScope.cancel()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Contrat du service au premier plan rempli d'abord, sur le thread
        // principal, avant tout verrou ou travail : valable pour un premier
        // démarrage, une reprise Always-on (intent null ou SERVICE_INTERFACE)
        // et l'action « redémarrer » de la notification.
        NotificationManager.ensureForeground(this)
        // Always-on VPN restarts from OS deliver intent.action == SERVICE_INTERFACE or null intent.
        // Reset any stuck start lock left by a killed process to allow setupVpnService() to run.
        val isSystemVpnStart = intent == null || intent.action == SERVICE_INTERFACE
        if (isSystemVpnStart) {
            unlockStart()
        }
        if (!tryLockStart()) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: Start already in progress")
            return START_NOT_STICKY
        }
        acquireVpnWakeLock()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service command received, systemVpnStart=$isSystemVpnStart")
        // Établissement du VPN et démarrage du cœur : déplacés hors du thread
        // principal (prepare(), establish() et le démarrage natif du relais TUN
        // ne doivent plus bloquer le service). startForeground() reste, lui,
        // synchronisé ci-dessus sur le thread principal, comme l'exige Android.
        startService()
        return START_STICKY
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        if (CoreServiceManager.isRunning()) {
            // Le cœur tourne déjà (second appui, intent Always-on, redémarrage
            // depuis la notification) : conserver la connexion existante et ne
            // jamais l'arrêter. Publier l'état réel au lieu d'un échec fictif.
            val verified = CoreServiceManager.isConnectionVerified()
            LogUtil.w(
                AppConfig.TAG,
                "StartCore-VPN: Core already running, keeping the existing connection (verified=$verified)",
            )
            MessageHelper.sendMsg2UI(
                this,
                if (verified) AppConfig.MSG_STATE_START_SUCCESS else AppConfig.MSG_STATE_CONNECTING,
                "",
            )
            unlockStart()
            return
        }
        if (startJob?.isActive == true) {
            // Un démarrage est déjà en cours (verrou posé par onStartCommand) :
            // ignorer le doublon au lieu d'exécuter deux établissements TUN en
            // concurrence. Le verrou est rendu par le finally du démarrage en
            // cours ; unlockStart() ici reste sans effet néfaste.
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: Duplicate start while a start is already in progress")
            unlockStart()
            return
        }
        startJob = serviceScope.launch {
            try {
                // Opérations lourdes hors du thread principal : préparation de
                // la permission, établissement de l'interface TUN et démarrage
                // du relais natif.
                val setupFailure = setupVpnService()
                if (setupFailure != null) {
                    LogUtil.e(AppConfig.TAG, "StartCore-VPN: Setup failed: $setupFailure")
                    releaseVpnWakeLock()
                    publishStartFailure(setupFailure, "Établissement du VPN impossible")
                    // Stop service if setup fails to avoid infinite restart loops (START_STICKY)
                    stopSelf()
                    return@launch
                }
                if (!::mInterface.isInitialized) {
                    LogUtil.e(AppConfig.TAG, "StartCore-VPN: Interface not initialized")
                    releaseVpnWakeLock()
                    publishStartFailure("vpn_configure_failed", "Établissement du VPN impossible")
                    stopSelf()
                    return@launch
                }
                if (!CoreServiceManager.startCoreLoop(mInterface)) {
                    LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
                    publishStartFailure("core_start_failed", "Le moteur Xray n'a pas démarré")
                    stopAllService()
                    return@launch
                }

                // Start LAN sharing if enabled in settings.
                RootLanSharing.startClientSharing(this@CoreVpnService)
            } finally {
                unlockStart()
            }
        }
    }

    override fun stopService() {
        CoreServiceManager.markUserStopped()
        stopAllService(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean {
        return super<VpnService>.setUnderlyingNetworks(networks)
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     *
     * @return null en cas de succès, sinon un code court de motif qui est
     * publié vers l'interface : un échec de préparation ne doit jamais laisser
     * l'appelant dans un état « inconnu » à attendre un message qui ne viendra pas.
     */
    private fun setupVpnService(): String? {
        val prepare = prepare(this)
        if (prepare != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            return "vpn_permission_required"
        }

        if (configureVpnService() != true) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            return "vpn_configure_failed"
        }

        if (!runTun2socks()) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Le relais TUN n'a pas démarré")
            stopAllService()
            return "tun_start_failed"
        }
        return null
    }

    /**
     * Publie un échec de démarrage avec un motif. `content` reste lisible par
     * l'interface classique, `reason` est le code exploité par le laboratoire.
     */
    private fun publishStartFailure(reason: String, message: String) {
        val mode = if (com.v2ray.ang.core.LaboratoryTunnelBridge.isActive(this)) {
            com.v2ray.ang.core.LaboratoryTunnelBridge.MODE_LABORATORY
        } else {
            com.v2ray.ang.core.LaboratoryTunnelBridge.MODE_CLASSIC
        }
        com.v2ray.ang.core.LaboratoryTunnelBridge.writeStatus(
            this,
            com.v2ray.ang.core.LaboratoryTunnelBridge.STATE_START_FAILED,
            mode,
            reason,
        )
        com.v2ray.ang.helper.MessageHelper.sendMsg2UI(
            this,
            AppConfig.MSG_STATE_START_FAILURE,
            message,
            null,
            reason,
        )
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun configureVpnService(): Boolean {
        val builder = Builder()

        // setBlocking ne configure que le mode de lecture du descripteur TUN ; cela ne
        // correspond pas au VPN toujours activé ni au verrouillage système Android.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            MmkvManager.decodeSettingsBool(AppConfig.PREF_KILL_SWITCH, false) == true
        ) {
            builder.setBlocking(true)
        }

        // Configure network settings (addresses, routing and DNS)
        configureNetworkSettings(builder)

        // Configure app-specific settings (session name and per-app proxy)
        configurePerAppProxy(builder)

        // Close the old interface since the parameters have been changed
        try {
            if (::mInterface.isInitialized) {
                mInterface.close()
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Failed to close old interface", e)
        }

        // Configure platform-specific features
        configurePlatformFeatures(builder)

        // Create a new interface using the builder and save the parameters
        try {
            mInterface = builder.establish() ?: run {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission VPN refusée ou interface indisponible")
                stopAllService()
                return false
            }
            isRunning = true
            return true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish VPN interface", e)
            stopAllService()
        }
        return false
    }

    /**
     * Configures the basic network settings for the VPN.
     * This includes IP addresses, routing rules, and DNS servers.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configureNetworkSettings(builder: Builder) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()

        // Configure IPv4 settings
        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        // Configure routing rules
        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        // Configure IPv6 if enabled
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3) // Currently only 1/8 of total IPv6 is in use
                builder.addRoute("fc00::", 18) // Xray-core default FakeIPv6 Pool
            } else {
                builder.addRoute("::", 0)
            }
        }

        // Configure DNS servers
        //if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
        //  builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        //} else {
        SettingsManager.getVpnDnsServers().forEach {
            if (Utils.isPureIpAddress(it)) {
                builder.addDnsServer(it)
            }
        }

        //builder.setSession(V2RayServiceManager.getRunningServerName())
    }

    /**
     * Configures platform-specific VPN features for different Android versions.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configurePlatformFeatures(builder: Builder) {
        // Android Q (API 29) and above: Configure metering and HTTP proxy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    /**
     * Configures per-app proxy rules for the VPN builder.
     *
     * - If per-app proxy is not enabled, disallow the VPN service's own package.
     * - If no apps are selected, disallow the VPN service's own package.
     * - If bypass mode is enabled, disallow all selected apps (including self).
     * - If proxy mode is enabled, only allow the selected apps (excluding self).
     *
     * @param builder The VPN Builder to configure.
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID

        // If per-app proxy is not enabled, disallow the VPN service's own package and return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        // If no apps are selected, disallow the VPN service's own package and return
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        // Handle the VPN service's own package according to the mode
        if (bypassApps) apps.add(selfPackageName) else apps.remove(selfPackageName)

        apps.forEach {
            try {
                if (bypassApps) {
                    // In bypass mode, disallow the selected apps
                    builder.addDisallowedApplication(it)
                } else {
                    // In proxy mode, only allow the selected apps
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to configure app", e)
            }
        }
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks(): Boolean {
        if (SettingsManager.isUsingHevTun()) {
            tun2SocksService = TProxyService(
                context = applicationContext,
                vpnInterface = mInterface,
                isRunningProvider = { isRunning },
                restartCallback = { runTun2socks() }
            )
        } else {
            tun2SocksService = null
        }

        return tun2SocksService?.startTun2Socks() ?: true
    }

    private fun stopAllService(isForced: Boolean = true) {
        if (stopJob?.isActive == true) return
        stopJob = serviceScope.launch {
            performStop(closeInterface = isForced, stopService = isForced)
        }
    }

    private suspend fun performStop(closeInterface: Boolean, stopService: Boolean) {
        RootLanSharing.stopClientSharing(this)
        val stopped = runCatching {
            CoreServiceManager.stopCoreLoop(onCoreStopped = {
                // Keep the VPN descriptor alive until native stopLoop has returned.
                tun2SocksService?.stopTun2Socks()
                tun2SocksService = null
                isRunning = false
                if (closeInterface) {
                    try {
                        if (::mInterface.isInitialized) mInterface.close()
                        LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed after core stop")
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface", e)
                    }
                }
            })
        }
            .onFailure { LogUtil.e(AppConfig.TAG, "StartCore-VPN: Core stop failed", it) }
            .getOrDefault(false)
        if (!stopped) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Core stop did not complete; keeping VPN resources open")
            return
        }

        releaseVpnWakeLock()
        unlockStart()
        if (stopService) stopSelf()
    }

    /** Garder le CPU du processus daemon actif écran éteint pour traiter le TUN et la reconnexion. */
    @Synchronized
    private fun acquireVpnWakeLock() {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_VPN_WAKE_LOCK, true)) return
        try {
            vpnWakeLockLease?.let {
                it.acquire()
                return
            }
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:VpnTunnel",
            ).apply { setReferenceCounted(false) }
            vpnWakeLockLease = VpnWakeLockLease(
                acquireLock = { if (!wakeLock.isHeld) wakeLock.acquire() },
                releaseLock = { if (wakeLock.isHeld) wakeLock.release() },
            ).also { it.acquire() }
        } catch (e: Exception) {
            LogUtil.e(
                AppConfig.TAG,
                "StartCore-VPN: Failed to acquire screen-off CPU lock for profile ${MmkvManager.getSelectServer().orEmpty()}",
                e,
            )
        }
    }

    @Synchronized
    private fun releaseVpnWakeLock() {
        runCatching { vpnWakeLockLease?.release() }
            .onFailure {
                LogUtil.e(
                    AppConfig.TAG,
                    "StartCore-VPN: Failed to release screen-off CPU lock for profile ${MmkvManager.getSelectServer().orEmpty()}",
                    it,
                )
            }
        vpnWakeLockLease = null
    }

    fun tryLockStart(): Boolean {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: tryLockStart: ${isStartingLock.get()}")
        return isStartingLock.compareAndSet(false, true)
    }

    fun unlockStart() {
        isStartingLock.set(false)
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: unlockStart")
    }
}
