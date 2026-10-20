package com.v2ray.ang.ipfinder

import kotlinx.coroutines.CancellationException

data class IpFinderPreflightInput(
    val airplaneModeEnabled: Boolean,
    val selectedProfileExists: Boolean,
    val vpnPermissionRequired: Boolean,
    val vpnPermissionGranted: Boolean,
    val vpnServiceActive: Boolean,
)

enum class IpFinderPreflightDecision {
    AIRPLANE_MODE_ON,
    PROFILE_REQUIRED,
    REQUEST_VPN_PERMISSION,
    CONFIRM_VPN_DISCONNECT,
    START_SEARCH,
}

object IpFinderPreflight {
    fun decide(input: IpFinderPreflightInput): IpFinderPreflightDecision = when {
        input.airplaneModeEnabled -> IpFinderPreflightDecision.AIRPLANE_MODE_ON
        !input.selectedProfileExists -> IpFinderPreflightDecision.PROFILE_REQUIRED
        input.vpnPermissionRequired && !input.vpnPermissionGranted -> IpFinderPreflightDecision.REQUEST_VPN_PERMISSION
        input.vpnServiceActive -> IpFinderPreflightDecision.CONFIRM_VPN_DISCONNECT
        else -> IpFinderPreflightDecision.START_SEARCH
    }
}

sealed interface IpFinderVpnConnectionResult {
    data object Connected : IpFinderVpnConnectionResult
    data class Failed(val cause: String) : IpFinderVpnConnectionResult
}

interface IpFinderFoundActionPort {
    fun selectedProfileExists(): Boolean
    fun currentCellularAddress(): String?
    fun currentCellularAddresses(): List<String> = listOfNotNull(currentCellularAddress())
    suspend fun startVpn()
    suspend fun awaitVpnConnected(timeoutMillis: Long): IpFinderVpnConnectionResult
}

sealed interface IpFinderFoundActionResult {
    data class Found(val address: String, val rule: String, val vpnConnected: Boolean) : IpFinderFoundActionResult
    data class RetrySearch(val address: String?) : IpFinderFoundActionResult
    data class VpnFailed(val address: String, val cause: String) : IpFinderFoundActionResult
    data object ProfileRequired : IpFinderFoundActionResult
}

/** Confirme l'adresse une seconde fois et ne démarre le VPN qu'après validation de la règle. */
class IpFinderFoundActionCoordinator(
    private val port: IpFinderFoundActionPort,
    private val rulesText: String,
) {
    suspend fun handle(
        candidateRule: String,
        connectVpnAutomatically: Boolean,
    ): IpFinderFoundActionResult {
        if (!port.selectedProfileExists()) return IpFinderFoundActionResult.ProfileRequired

        val currentAddresses = port.currentCellularAddresses()
        val currentAddress = currentAddresses.firstOrNull { address ->
            IpRuleMatcher.match(rulesText, address).matches
        } ?: return IpFinderFoundActionResult.RetrySearch(currentAddresses.firstOrNull())
        val currentMatch = IpRuleMatcher.match(rulesText, currentAddress)

        if (!connectVpnAutomatically) {
            return IpFinderFoundActionResult.Found(
                currentAddress,
                currentMatch.triggeringRule ?: candidateRule,
                vpnConnected = false,
            )
        }

        return try {
            port.startVpn()
            when (val result = port.awaitVpnConnected(VPN_CONNECT_TIMEOUT_MILLIS)) {
                IpFinderVpnConnectionResult.Connected -> IpFinderFoundActionResult.Found(
                    currentAddress,
                    currentMatch.triggeringRule ?: candidateRule,
                    vpnConnected = true,
                )
                is IpFinderVpnConnectionResult.Failed -> IpFinderFoundActionResult.VpnFailed(currentAddress, result.cause)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            IpFinderFoundActionResult.VpnFailed(currentAddress, e.message?.takeIf(String::isNotBlank) ?: e.javaClass.simpleName)
        }
    }

    companion object {
        const val VPN_CONNECT_TIMEOUT_MILLIS = 20_000L
    }
}
