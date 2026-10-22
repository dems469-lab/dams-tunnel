package com.v2ray.ang.ui.main

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.v2ray.ang.ipfinder.IpFinderCellularAddressSelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.net.InetAddress

/** Publie uniquement l'adresse actuellement attribuée au réseau cellulaire. */
internal class MobileIpMonitor(context: Context) : Closeable {
    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val _mobileIp = MutableStateFlow<String?>(null)
    val mobileIp: StateFlow<String?> = _mobileIp.asStateFlow()
    private val addressesByNetwork = mutableMapOf<Network, List<InetAddress>>()
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                addressesByNetwork[network] = usableAddresses(linkProperties)
                publish()
            }

            override fun onLost(network: Network) {
                addressesByNetwork.remove(network)
                publish()
            }
        }
        callback = networkCallback
        runCatching {
            connectivity.allNetworks.forEach { network ->
                if (isCellular(network)) {
                    connectivity.getLinkProperties(network)?.let { linkProperties ->
                        addressesByNetwork[network] = usableAddresses(linkProperties)
                    }
                }
            }
            publish()
            connectivity.registerNetworkCallback(cellularRequest(), networkCallback)
        }.onFailure {
            addressesByNetwork.clear()
            _mobileIp.value = null
            callback = null
        }
    }

    private fun cellularRequest(): NetworkRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .build()

    private fun isCellular(network: Network): Boolean = connectivity
        .getNetworkCapabilities(network)
        ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

    private fun usableAddresses(linkProperties: LinkProperties): List<InetAddress> =
        linkProperties.linkAddresses
            .map { it.address }
            .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }

    private fun publish() {
        _mobileIp.value = IpFinderCellularAddressSelector.select(
            addressesByNetwork.values.asSequence().flatten().asIterable()
        )
    }

    override fun close() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        addressesByNetwork.clear()
        _mobileIp.value = null
    }
}
