package com.v2ray.ang.ipfinder

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import java.net.Inet4Address
import java.net.InetAddress
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
    override fun currentCellularAddress(): String? = currentCellularAddresses().firstOrNull()

    override fun currentCellularAddresses(): List<String> = connectivity.allNetworks
        .asSequence()
        .filter { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        }
        .flatMap { network -> connectivity.getLinkProperties(network)?.let(::usableAddresses).orEmpty().asSequence() }
        .distinct()
        .sortedBy { if (isIpv4(it)) 0 else 1 }
        .toList()

    override suspend fun requestAirplaneMode(enabled: Boolean): IpFinderAirplaneRequest = requestMode(enabled)

    override suspend fun awaitAirplaneMode(enabled: Boolean, timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (IpFinderRecoveryStore.isAirplaneModeEnabled(appContext) != enabled) delay(200)
            true
        } ?: false

    override suspend fun awaitCellularDisconnect(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            cellularAddressUpdates().first { it.isEmpty() }
            true
        } ?: false

    override suspend fun awaitCellularAddress(timeoutMillis: Long): String? =
        withTimeoutOrNull(timeoutMillis) {
            cellularAddressUpdates().first { it.isNotEmpty() }.firstOrNull()
        }

    override suspend fun awaitCellularAddresses(timeoutMillis: Long): List<String>? =
        withTimeoutOrNull(timeoutMillis) {
            cellularAddressUpdates().first { it.isNotEmpty() }
        }

    private fun cellularAddressUpdates() = callbackFlow {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        val addressesByNetwork = mutableMapOf<Network, List<String>>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Android livre ensuite LinkProperties via onLinkPropertiesChanged.
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                // Le NetworkRequest ne sélectionne que les réseaux cellulaires. Utiliser les
                // propriétés fournies par l'événement évite une lecture synchrone sujette aux courses.
                addressesByNetwork[network] = usableAddresses(linkProperties)
                trySend(addressesByNetwork.values.flatten().distinct().sortedBy { if (isIpv4(it)) 0 else 1 })
            }

            override fun onLost(network: Network) {
                addressesByNetwork.remove(network)
                trySend(addressesByNetwork.values.flatten().distinct().sortedBy { if (isIpv4(it)) 0 else 1 })
            }
        }

        connectivity.registerNetworkCallback(request, callback)
        trySend(currentCellularAddresses())
        awaitClose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }

    private fun usableAddresses(linkProperties: LinkProperties): List<String> =
        IpFinderCellularAddressSelector.selectAll(linkProperties.linkAddresses.map { it.address })

    private fun isIpv4(address: String): Boolean = address.substringBefore('%').count { it == '.' } == 3
}

/** Les règles IP Finder valident actuellement des motifs IPv4 : choisir cette famille d'abord
 * évite de manquer une IPv4 cellulaire simplement parce qu'Android liste l'IPv6 avant elle. */
internal object IpFinderCellularAddressSelector {
    fun selectAll(addresses: Iterable<InetAddress>): List<String> {
        val usable = addresses.filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
        return usable.sortedBy { if (it is Inet4Address) 0 else 1 }
            .map { it.hostAddress.substringBefore('%') }
            .distinct()
    }

    fun select(addresses: Iterable<InetAddress>): String? = selectAll(addresses).firstOrNull()
}
