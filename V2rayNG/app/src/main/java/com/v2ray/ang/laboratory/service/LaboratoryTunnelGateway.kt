package com.v2ray.ang.laboratory.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.laboratory.multipath.TunnelGateway
import com.v2ray.ang.service.CoreVpnService
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Adaptateur réel entre l'interface du laboratoire et le service VPN unique.
 *
 * - aucun second service VPN n'est créé : [CoreVpnService] reste le seul TUN ;
 * - le démarrage ne passe pas par `LauncherManager` afin de ne jamais appeler
 *   `setSelectServer` (la sélection de production reste intacte) ;
 * - la réussite n'est validée que par `MSG_STATE_START_SUCCESS`, émis après la
 *   vérification réelle du trafic par le moteur.
 */
class LaboratoryTunnelGateway(context: Context) : TunnelGateway {

    private val appContext = context.applicationContext
    private val lastState = MutableStateFlow<Int?>(null)

    private var registered = false

    val serviceState: StateFlow<Int?> = lastState.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val what = intent?.getIntExtra("key", 0) ?: 0
            if (what != 0) lastState.value = what
        }
    }

    /** À appeler quand l'écran devient visible. */
    fun register() {
        if (registered) return
        registered = true
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            Utils.receiverFlags(),
        )
        MessageHelper.sendMsg2Service(appContext, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    /** À appeler quand l'écran disparaît. */
    fun unregister() {
        if (!registered) return
        registered = false
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    override fun isTunnelRunning(): Boolean = lastState.value in RUNNING_STATES

    override suspend fun stopClassicTunnel(): Boolean {
        val state = lastState.value
        if (state == AppConfig.MSG_STATE_NOT_RUNNING || state == AppConfig.MSG_STATE_STOP_SUCCESS) {
            return true
        }
        MessageHelper.sendMsg2Service(appContext, AppConfig.MSG_STATE_STOP, "")
        val stopped = awaitState(STOPPED_STATES, STOP_TIMEOUT_MILLIS) != null
        if (stopped) {
            // Laisse au service le temps de libérer le TUN et ses sockets avant
            // de recréer une interface : jamais deux interfaces simultanées.
            kotlinx.coroutines.delay(TUN_RELEASE_DELAY_MILLIS)
        }
        return stopped
    }

    override suspend fun startLaboratoryTunnel(): Boolean {
        val started = runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, CoreVpnService::class.java),
            )
        }.isSuccess
        if (!started) return false
        val state = awaitState(
            RUNNING_STATES + AppConfig.MSG_STATE_START_FAILURE,
            START_TIMEOUT_MILLIS,
        )
        return state == AppConfig.MSG_STATE_START_SUCCESS
    }

    override suspend fun stopTunnel(): Boolean {
        if (lastState.value == AppConfig.MSG_STATE_NOT_RUNNING) return true
        MessageHelper.sendMsg2Service(appContext, AppConfig.MSG_STATE_STOP, "")
        return awaitState(STOPPED_STATES, STOP_TIMEOUT_MILLIS) != null
    }

    private suspend fun awaitState(candidates: Set<Int>, timeoutMillis: Long): Int? =
        withTimeoutOrNull(timeoutMillis) {
            lastState.filterNotNull().first { it in candidates }
        }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 12_000L
        private const val START_TIMEOUT_MILLIS = 40_000L
        private const val TUN_RELEASE_DELAY_MILLIS = 700L

        private val RUNNING_STATES = setOf(
            AppConfig.MSG_STATE_START_SUCCESS,
            AppConfig.MSG_STATE_CONNECTING,
            AppConfig.MSG_STATE_RECONNECTING,
        )

        private val STOPPED_STATES = setOf(
            AppConfig.MSG_STATE_STOP_SUCCESS,
            AppConfig.MSG_STATE_NOT_RUNNING,
        )
    }
}
