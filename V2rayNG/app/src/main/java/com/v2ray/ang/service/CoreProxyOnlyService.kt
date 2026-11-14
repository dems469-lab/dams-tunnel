package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CoreProxyOnlyService : Service(), ServiceControl {
    // Handler d'exception : une erreur qui s'échappe d'une coroutine tue le
    // processus :daemon ; on journalise au lieu de laisser le crash survenir.
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            LogUtil.e(AppConfig.TAG, "StartCore-Proxy: uncaught exception in service scope", error)
        }
    )
    private var setupJob: Job? = null

    /**
     * Initializes the service.
     */
    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Service created")
        CoreServiceManager.serviceControl = this
    }

    /**
     * Handles the start command for the service.
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground(this)
        LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Service command received")

        if (setupJob?.isActive == true || CoreServiceManager.isRunning()) {
            LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Core is already running")
            return START_STICKY
        }

        setupJob = serviceScope.launch {
            if (!CoreServiceManager.startCoreLoop(null)) {
                LogUtil.e(AppConfig.TAG, "StartCore-Proxy: Failed to start core loop")
                stopSelf()
            }
        }

        return START_STICKY
    }

    /**
     * Destroys the service.
     */
    override fun onDestroy() {
        super.onDestroy()
        setupJob?.cancel()
        // Symétrique de CoreVpnService : sans detach, le récepteur reste
        // enregistré sur ce service détruit et empêche un futur service de
        // se rattacher (receiverService jamais remis à null).
        CoreServiceManager.detachServiceReceiver(this)
        serviceScope.launch {
            setupJob?.join()
            val stopped = runCatching { CoreServiceManager.stopCoreLoop() }
                .onFailure { LogUtil.e(AppConfig.TAG, "StartCore-Proxy: core stop failed", it) }
                .getOrDefault(false)
            if (!stopped) LogUtil.e(AppConfig.TAG, "StartCore-Proxy: core stop timed out; cleanup remains pending")
            CoreServiceManager.clearServiceControl(this@CoreProxyOnlyService)
            serviceScope.cancel()
        }
    }

    /**
     * Gets the service instance.
     * @return The service instance.
     */
    override fun getService(): Service {
        return this
    }

    /**
     * Starts the service.
     */
    override fun startService() {
        // do nothing
    }

    /**
     * Stops the service.
     */
    override fun stopService() {
        CoreServiceManager.markUserStopped()
        stopSelf()
    }

    /**
     * Protects the VPN socket.
     * @param socket The socket to protect.
     * @return True if the socket is protected, false otherwise.
     */
    override fun vpnProtect(socket: Int): Boolean {
        return true
    }

    /**
     * Binds the service.
     * @param intent The intent.
     * @return The binder.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Attaches the base context to the service.
     * @param newBase The new base context.
     */
    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }
}
