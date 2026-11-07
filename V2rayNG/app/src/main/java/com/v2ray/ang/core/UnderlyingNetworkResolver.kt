package com.v2ray.ang.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil

/**
 * Résout un domaine via le réseau sous-jacent, jamais via le VPN.
 *
 * Le réseau est choisi par [selectUnderlyingNetwork] : un réseau avec Internet et sans
 * transport VPN, de préférence validé. La requête est ensuite liée à ce réseau avec
 * [Network.getAllByName], donc elle ne peut pas emprunter le DNS du tunnel.
 */
internal class UnderlyingNetworkResolver(private val context: Context) {

    fun resolve(domain: String, preferIpv6: Boolean): List<String>? {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        val candidates = connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            Candidate(
                network = network,
                descriptor = NetworkCandidate(
                    id = network.networkHandle,
                    isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                ),
            )
        }

        val selected = selectUnderlyingNetwork(candidates.map { it.descriptor }) ?: run {
            LogUtil.w(AppConfig.TAG, "DNS pre-resolution: no non-VPN network available")
            return null
        }
        val network = candidates.firstOrNull { it.descriptor.id == selected.id }?.network ?: return null

        return try {
            val ips = network.getAllByName(domain)
                .mapNotNull { it.hostAddress }
                .filter { it.isNotBlank() }
            if (ips.isEmpty()) null else sortResolvedAddresses(ips, preferIpv6)
        } catch (e: Exception) {
            LogUtil.w(
                AppConfig.TAG,
                "DNS pre-resolution: underlying lookup failed (${e.javaClass.simpleName})",
            )
            null
        }
    }

    private data class Candidate(val network: Network, val descriptor: NetworkCandidate)
}
