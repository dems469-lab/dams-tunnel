package com.v2ray.ang.reconfig.net

import com.v2ray.ang.reconfig.data.ReconfigPingState
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PingOutcome(
    val state: ReconfigPingState,
    val latencyMs: Long = -1L,
)

/**
 * Ping TCP vers l'adresse de connexion.
 *
 * L'application n'a pas accès à ICMP (aucune permission, aucun root) : la
 * latence affichée est donc un temps d'établissement de connexion TCP, toujours
 * annoncé comme tel dans l'interface. Un socket est systématiquement fermé.
 */
internal object ServerPingManager {
    const val DEFAULT_TIMEOUT_MS = 2500

    suspend fun ping(
        host: String,
        port: Int,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): PingOutcome = withContext(dispatcher) {
        if (host.isBlank() || port !in 1..65535) {
            return@withContext PingOutcome(ReconfigPingState.ERROR)
        }
        val startedAt = System.nanoTime()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            PingOutcome(ReconfigPingState.SUCCESS, elapsedMs.coerceAtLeast(0L))
        } catch (_: SocketTimeoutException) {
            PingOutcome(ReconfigPingState.TIMEOUT)
        } catch (_: UnknownHostException) {
            PingOutcome(ReconfigPingState.DNS_ERROR)
        } catch (_: NoRouteToHostException) {
            PingOutcome(ReconfigPingState.HOST_UNREACHABLE)
        } catch (_: ConnectException) {
            PingOutcome(ReconfigPingState.HOST_UNREACHABLE)
        } catch (_: IOException) {
            PingOutcome(ReconfigPingState.CONNECTION_ERROR)
        } catch (_: Exception) {
            PingOutcome(ReconfigPingState.ERROR)
        }
    }
}
