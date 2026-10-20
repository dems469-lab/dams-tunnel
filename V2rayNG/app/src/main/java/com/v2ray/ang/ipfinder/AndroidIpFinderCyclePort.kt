package com.v2ray.ang.ipfinder

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.awaitClose

class AndroidIpFinderCyclePort(
    context: Context,
    private val requestMode: suspend (Boolean) -> IpFinderAirplaneRequest
) : IpFinderCyclePort {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)

    override fun isWifiConnected(): Boolean = connectivity.allNetworks.any { network ->
        connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /** Ne parcourt que les réseaux marqués CELLULAR : l'IP Wi-Fi ou VPN ne peut être renvoyée. */
    override fun currentCellularAddress(): String? = connectivity.allNetworks
        .asSequence()
        .filter { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        }
        .mapNotNull { network -> connectivity.getLinkProperties(network)?.let(::firstUsableAddress) }
        .firstOrNull()

    override suspend fun requestAirplaneMode(enabled: Boolean): IpFinderAirplaneRequest = requestMode(enabled)

    override suspend fun awaitAirplaneMode(enabled: Boolean, timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (IpFinderRecoveryStore.isAirplaneModeEnabled(appContext) != enabled) delay(200)
            true
        } ?: false

    override suspend fun awaitCellularDisconnect(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            cellularAddressUpdates().first { it == null }
            true
        } ?: false

    override suspend fun awaitCellularAddress(timeoutMillis: Long): String? =
        withTimeoutOrNull(timeoutMillis) {
            cellularAddressUpdates().first { !it.isNullOrBlank() }
        }

    private fun cellularAddressUpdates() = callbackFlow {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // LinkProperties are delivered asynchronously by onLinkPropertiesChanged.
                trySend(currentCellularAddress())
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val cellular = connectivity.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                if (cellular) trySend(firstUsableAddress(linkProperties) ?: currentCellularAddress())
            }

            override fun onLost(network: Network) {
                trySend(currentCellularAddress())
            }
        }

        connectivity.registerNetworkCallback(request, callback)
        trySend(currentCellularAddress())
        awaitClose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }

    private fun firstUsableAddress(linkProperties: LinkProperties): String? =
        linkProperties.linkAddresses.asSequence()
            .map { it.address }
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
            ?.substringBefore('%')
}
