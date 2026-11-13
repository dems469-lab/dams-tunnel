package com.v2ray.ang.laboratory.service

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LaboratoryTunnelBridge
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.laboratory.multipath.TunnelGateway
import com.v2ray.ang.service.CoreVpnService
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Adaptateur réel entre l'interface du laboratoire et le service VPN unique.
 *
 * - aucun second service VPN n'est créé : [CoreVpnService] reste le seul TUN ;
 * - le démarrage ne passe pas par `LauncherManager` afin de ne jamais appeler
 *   `setSelectServer` (la sélection de production reste intacte) ;
 * - la réussite n'est validée que par `MSG_STATE_START_SUCCESS`, émis après la
 *   vérification réelle du trafic par le moteur ;
 * - l'arrêt n'est validé que par `MSG_STATE_STOP_SUCCESS`, émis après la
 *   libération effective du TUN. Un échec produit `MSG_STATE_STOP_FAILURE`
 *   avec un motif, au lieu d'un silence interprété comme une confirmation.
 * - chaque ordre est envoyé en broadcast ordonné : l'accusé de réception du
 *   démon distingue « service absent » de « ordre reçu mais pas encore exécuté ».
 */
class LaboratoryTunnelGateway(
    context: Context,
    private val stopTimeoutMillis: Long = DEFAULT_STOP_TIMEOUT_MILLIS,
    private val startTimeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS,
    private val ackTimeoutMillis: Long = DEFAULT_ACK_TIMEOUT_MILLIS,
) : TunnelGateway {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val lastState = MutableStateFlow<Int?>(null)
    private val lastReason = MutableStateFlow<String?>(null)
    private val stateKnown = MutableStateFlow(false)

    private var registered = false

    /** Dernier message d'état reçu du démon (null tant qu'aucun n'est arrivé). */
    val serviceState: StateFlow<Int?> = lastState.asStateFlow()

    /** Motif technique du dernier échec (arrêt ou démarrage). Jamais de secret. */
    val failureReason: StateFlow<String?> = lastReason.asStateFlow()

    /** True dès que l'état réel du service a été établi (même « non démarré »). */
    val serviceStateKnown: StateFlow<Boolean> = stateKnown.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val what = intent?.getIntExtra("key", 0) ?: 0
            if (what == 0) return
            if (what == AppConfig.MSG_STATE_STOP_FAILURE ||
                what == AppConfig.MSG_STATE_START_FAILURE
            ) {
                val reason = intent?.getStringExtra(MessageHelper.EXTRA_REASON)
                    ?.takeIf { it.isNotBlank() }
                lastReason.value = reason
            }
            stateKnown.value = true
            lastState.value = what
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
        scope.launch { refreshState() }
    }

    /** À appeler quand l'écran disparaît. */
    fun unregister() {
        if (!registered) return
        registered = false
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    /** Libère le scope interne (appelé par le ViewModel). */
    fun release() {
        unregister()
        scope.cancel()
    }

    override fun isTunnelRunning(): Boolean = lastState.value in RUNNING_STATES

    fun isStateKnown(): Boolean = stateKnown.value

    /**
     * Interroge le démon pour connaître l'état réel du service.
     *
     * @return true si un démon a répondu (son état arrive alors par broadcast).
     */
    suspend fun refreshState(): Boolean {
        val handled = requestAck(AppConfig.MSG_REGISTER_CLIENT)
        delay(STATE_SETTLE_MILLIS)
        stateKnown.value = true
        if (!handled && lastState.value == null) {
            // Aucun démon n'a répondu : le tunnel ne peut pas être actif.
            lastState.value = AppConfig.MSG_STATE_NOT_RUNNING
        }
        return handled
    }

    override suspend fun stopClassicTunnel(): Boolean = stopTunnel()

    override suspend fun startLaboratoryTunnel(): Boolean {
        lastReason.value = null
        val started = runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, CoreVpnService::class.java),
            )
        }.isSuccess
        if (!started) {
            lastReason.value = "service_start_rejected"
            return false
        }
        val state = awaitState(
            RUNNING_STATES + FAILURE_STATES,
            startTimeoutMillis,
        )
        if (state == null) {
            lastReason.value = "start_timeout"
            return false
        }
        return state == AppConfig.MSG_STATE_START_SUCCESS
    }

    override suspend fun stopTunnel(): Boolean {
        if (isKnownStopped()) return true

        // Deux envois au maximum : le premier peut arriver avant que le démon
        // n'ait fini de s'attacher (processus recréé, service en cours de
        // création), le second couvre ce cas sans jamais multiplier les arrêts.
        var acknowledged = requestAck(AppConfig.MSG_STATE_STOP)
        if (!acknowledged) {
            delay(STOP_RETRY_DELAY_MILLIS)
            acknowledged = requestAck(AppConfig.MSG_STATE_STOP)
        }

        if (!acknowledged) {
            // Aucun démon n'a reçu l'ordre. On ne conclut à l'arrêt que si
            // aucune preuve d'exécution ne subsiste.
            val stillRunning = hasRunningEvidence()
            if (stillRunning) {
                lastReason.value = "daemon_unreachable"
                LogUtil.w(TAG, "Laboratory stop: no daemon acknowledgement while tunnel evidence persists")
                return false
            }
            lastState.value = AppConfig.MSG_STATE_NOT_RUNNING
            stateKnown.value = true
            return true
        }

        val state = awaitState(
            STOPPED_STATES + FAILURE_STATES,
            stopTimeoutMillis,
        )
        return when {
            state == AppConfig.MSG_STATE_STOP_FAILURE -> false
            state == AppConfig.MSG_STATE_NOT_RUNNING ||
                state == AppConfig.MSG_STATE_STOP_SUCCESS -> {
                // Laisse au démon le temps de fermer réellement le TUN avant
                // qu'une nouvelle interface ne soit créée.
                delay(TUN_RELEASE_DELAY_MILLIS)
                true
            }

            else -> {
                lastReason.value = "stop_timeout"
                false
            }
        }
    }

    private fun isKnownStopped(): Boolean =
        lastState.value == AppConfig.MSG_STATE_NOT_RUNNING ||
            lastState.value == AppConfig.MSG_STATE_STOP_SUCCESS

    /**
     * Cherche une preuve que le tunnel tourne encore alors que le démon ne
     * répond pas : état publié récent ou service visible du même paquet.
     */
    private suspend fun hasRunningEvidence(): Boolean {
        val status = withContext(Dispatchers.IO) { LaboratoryTunnelBridge.readStatus(appContext) }
        val fresh = status != null &&
            status.state == LaboratoryTunnelBridge.STATE_RUNNING &&
            System.currentTimeMillis() - status.atMillis < EVIDENCE_MAX_AGE_MILLIS
        if (fresh) return true

        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val running = runCatching {
            manager.getRunningServices(Int.MAX_VALUE)
        }.getOrNull() ?: return false
        return running.any { it.service.className == CoreVpnService::class.java.name }
    }

    private suspend fun awaitState(candidates: Set<Int>, timeoutMillis: Long): Int? =
        withTimeoutOrNull(timeoutMillis) {
            lastState.filterNotNull().first { it in candidates }
        }

    /**
     * Envoie un ordre en broadcast ordonné et attend l'accusé de réception du
     * démon. `false` signifie qu'aucun démon n'a traité l'ordre.
     */
    private suspend fun requestAck(what: Int): Boolean = withTimeoutOrNull(ackTimeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            MessageHelper.sendMsg2ServiceForResult(appContext, what, "") { handled ->
                if (continuation.isActive) continuation.resume(handled)
            }
        }
    } ?: false

    companion object {
        private const val TAG = "DamsMultipath"

        /** Délai laissé au démon pour publier son état juste après l'accusé. */
        private const val STATE_SETTLE_MILLIS = 900L
        private const val TUN_RELEASE_DELAY_MILLIS = 700L
        private const val STOP_RETRY_DELAY_MILLIS = 1_200L
        private const val EVIDENCE_MAX_AGE_MILLIS = 120_000L

        const val DEFAULT_STOP_TIMEOUT_MILLIS = 20_000L
        const val DEFAULT_START_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_ACK_TIMEOUT_MILLIS = 1_500L

        private val RUNNING_STATES = setOf(
            AppConfig.MSG_STATE_START_SUCCESS,
            AppConfig.MSG_STATE_CONNECTING,
            AppConfig.MSG_STATE_RECONNECTING,
        )

        private val STOPPED_STATES = setOf(
            AppConfig.MSG_STATE_STOP_SUCCESS,
            AppConfig.MSG_STATE_NOT_RUNNING,
        )

        private val FAILURE_STATES = setOf(
            AppConfig.MSG_STATE_STOP_FAILURE,
            AppConfig.MSG_STATE_START_FAILURE,
        )
    }
}
