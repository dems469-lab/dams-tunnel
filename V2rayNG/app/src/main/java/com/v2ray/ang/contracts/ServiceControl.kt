package com.v2ray.ang.contracts

import android.app.Service
import android.net.Network

interface ServiceControl {
    /**
     * Gets the service instance.
     * @return The service instance.
     */
    fun getService(): Service

    /**
     * Starts the service.
     */
    fun startService()

    /**
     * Stops the service.
     */
    fun stopService()

    /**
     * Protects the VPN socket.
     * @param socket The socket to protect.
     * @return True if the socket is protected, false otherwise.
     */
    fun vpnProtect(socket: Int): Boolean

    /**
     * Declares the networks the tunnel runs on top of.
     * Only meaningful for the VPN service, the other run modes have no interface to report.
     *
     * @param networks The upstream networks, null to let the system pick.
     * @return True if the networks were accepted.
     */
    fun setUnderlyingNetworks(networks: Array<Network>?): Boolean = false

    /**
     * Indique si le relais TUN qui transporte réellement les paquets est actif.
     *
     * Seul le mode VPN possède une interface et un relais ; les modes proxy et
     * root renvoient true (rien à surveiller). Un doute renvoie true : il ne
     * doit jamais faire démonter un tunnel qui fonctionne.
     */
    fun isTunnelRelayRunning(): Boolean = true
}
