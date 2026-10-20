package com.v2ray.ang.ipfinder

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class IpFinderCycleEvent {
    WIFI_ACTIVE,
    INITIAL_ADDRESS,
    AIRPLANE_ON_REQUESTED,
    SYSTEM_ACTIVITY_DETECTED,
    SYSTEM_ACTIVITY_NOT_DETECTED,
    AIRPLANE_ON_CONFIRMED,
    CELLULAR_LOST,
    AIRPLANE_OFF_REQUESTED,
    AIRPLANE_OFF_CONFIRMED,
    CELLULAR_RETURNED,
    FINAL_ADDRESS,
    RESTORE_FAILED,
    FOREGROUND_RETURN_TIMEOUT
}

enum class IpFinderCycleFailure {
    NO_INITIAL_CELLULAR_ADDRESS,
    ASSISTANT_REQUEST_REJECTED,
    AIRPLANE_ON_TIMEOUT,
    CELLULAR_DID_NOT_DISCONNECT,
    AIRPLANE_OFF_TIMEOUT,
    CELLULAR_RETURN_TIMEOUT,
    RECOVERY_STORAGE_FAILURE,
    FOREGROUND_RETURN_TIMEOUT
}

sealed interface IpFinderCycleResult {
    data class Success(val addressBefore: String, val addressAfter: String) : IpFinderCycleResult
    data class Failure(val reason: IpFinderCycleFailure) : IpFinderCycleResult
    data object Cancelled : IpFinderCycleResult
}

data class IpFinderAirplaneRequest(
    val accepted: Boolean,
    val systemActivityDetected: Boolean,
    val foregroundTimeout: Boolean = false
)

interface IpFinderCyclePort {
    fun isWifiConnected(): Boolean
    fun currentCellularAddress(): String?
    /** Toutes les adresses des interfaces cellulaires, sans adresse Wi-Fi ni VPN. */
    fun currentCellularAddresses(): List<String> = listOfNotNull(currentCellularAddress())
    suspend fun requestAirplaneMode(enabled: Boolean): IpFinderAirplaneRequest
    suspend fun awaitAirplaneMode(enabled: Boolean, timeoutMillis: Long): Boolean
    suspend fun awaitCellularDisconnect(timeoutMillis: Long): Boolean
    suspend fun awaitCellularAddress(timeoutMillis: Long): String?
    suspend fun awaitCellularAddresses(timeoutMillis: Long): List<String>? =
        awaitCellularAddress(timeoutMillis)?.let(::listOf)
}

interface IpFinderRecoveryFlag {
    fun isPending(): Boolean
    fun markPending(): Boolean
    fun clear()
}

/** Exécute exactement un cycle et conserve le drapeau jusqu'à confirmation du mode avion désactivé. */
class IpFinderCycleCoordinator(
    private val port: IpFinderCyclePort,
    private val recoveryFlag: IpFinderRecoveryFlag,
    private val onEvent: (IpFinderCycleEvent) -> Unit,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) {
    suspend fun runSingleCycle(): IpFinderCycleResult {
        if (port.isWifiConnected()) onEvent(IpFinderCycleEvent.WIFI_ACTIVE)
        val addressBefore = port.currentCellularAddress()
            ?: return IpFinderCycleResult.Failure(IpFinderCycleFailure.NO_INITIAL_CELLULAR_ADDRESS)
        onEvent(IpFinderCycleEvent.INITIAL_ADDRESS)

        try {
            // Persist first: a process death between this write and the toggle remains recoverable.
            if (!recoveryFlag.markPending()) {
                return IpFinderCycleResult.Failure(IpFinderCycleFailure.RECOVERY_STORAGE_FAILURE)
            }
            onEvent(IpFinderCycleEvent.AIRPLANE_ON_REQUESTED)
            val airplaneOnRequest = port.requestAirplaneMode(true)
            if (airplaneOnRequest.foregroundTimeout) {
                onEvent(IpFinderCycleEvent.FOREGROUND_RETURN_TIMEOUT)
                return IpFinderCycleResult.Failure(IpFinderCycleFailure.FOREGROUND_RETURN_TIMEOUT)
            }
            if (!airplaneOnRequest.accepted) {
                return IpFinderCycleResult.Failure(IpFinderCycleFailure.ASSISTANT_REQUEST_REJECTED)
            }
            onEvent(if (airplaneOnRequest.systemActivityDetected) {
                IpFinderCycleEvent.SYSTEM_ACTIVITY_DETECTED
            } else {
                IpFinderCycleEvent.SYSTEM_ACTIVITY_NOT_DETECTED
            })
            if (!port.awaitAirplaneMode(true, timeoutMillis)) {
                return IpFinderCycleResult.Failure(IpFinderCycleFailure.AIRPLANE_ON_TIMEOUT)
            }
            onEvent(IpFinderCycleEvent.AIRPLANE_ON_CONFIRMED)
            if (!port.awaitCellularDisconnect(timeoutMillis)) {
                return IpFinderCycleResult.Failure(IpFinderCycleFailure.CELLULAR_DID_NOT_DISCONNECT)
            }
            onEvent(IpFinderCycleEvent.CELLULAR_LOST)

            onEvent(IpFinderCycleEvent.AIRPLANE_OFF_REQUESTED)
            val airplaneOffRequest = port.requestAirplaneMode(false)
            if (airplaneOffRequest.foregroundTimeout) {
                onEvent(IpFinderCycleEvent.FOREGROUND_RETURN_TIMEOUT)
                return IpFinderCycleResult.Failure(IpFinderCycleFailure.FOREGROUND_RETURN_TIMEOUT)
            }
            if (!airplaneOffRequest.accepted) {
                return IpFinderCycleResult.Failure(IpFinderCycleFailure.ASSISTANT_REQUEST_REJECTED)
            }
            if (!port.awaitAirplaneMode(false, timeoutMillis)) {
                return IpFinderCycleResult.Failure(IpFinderCycleFailure.AIRPLANE_OFF_TIMEOUT)
            }
            recoveryFlag.clear()
            onEvent(IpFinderCycleEvent.AIRPLANE_OFF_CONFIRMED)

            val addressAfter = port.awaitCellularAddress(CELLULAR_RETURN_TIMEOUT_MILLIS)
                ?: return IpFinderCycleResult.Failure(IpFinderCycleFailure.CELLULAR_RETURN_TIMEOUT)
            onEvent(IpFinderCycleEvent.CELLULAR_RETURNED)
            onEvent(IpFinderCycleEvent.FINAL_ADDRESS)
            return IpFinderCycleResult.Success(addressBefore, addressAfter)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } finally {
            if (recoveryFlag.isPending()) {
                withContext(NonCancellable) {
                    val restored = runCatching {
                        port.requestAirplaneMode(false).accepted &&
                            port.awaitAirplaneMode(false, timeoutMillis)
                    }.getOrDefault(false)
                    if (restored) recoveryFlag.clear() else onEvent(IpFinderCycleEvent.RESTORE_FAILED)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 20_000L
        const val CELLULAR_RETURN_TIMEOUT_MILLIS = 45_000L
    }
}
