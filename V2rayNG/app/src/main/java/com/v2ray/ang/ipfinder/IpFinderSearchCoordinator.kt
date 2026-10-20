package com.v2ray.ang.ipfinder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull as coroutineWithTimeoutOrNull

interface IpFinderSearchClock {
    suspend fun <T> withTimeoutOrNull(timeoutMillis: Long, block: suspend () -> T): T?
    suspend fun pause(milliseconds: Long)
}

object SystemIpFinderSearchClock : IpFinderSearchClock {
    override suspend fun <T> withTimeoutOrNull(timeoutMillis: Long, block: suspend () -> T): T? =
        coroutineWithTimeoutOrNull(timeoutMillis) { block() }

    override suspend fun pause(milliseconds: Long) = delay(milliseconds)
}

interface IpFinderSearchPort {
    fun isAirplaneModeEnabled(): Boolean
    suspend fun runCycle(): IpFinderCycleResult
    fun currentCellularAddress(): String?
    fun currentCellularAddresses(): List<String> = listOfNotNull(currentCellularAddress())
    suspend fun restoreAirplaneMode(): Boolean
}

sealed interface IpFinderSearchResult {
    data class Found(val attempt: Int, val address: String, val rule: String) : IpFinderSearchResult
    data class Exhausted(val attempts: Int) : IpFinderSearchResult
    data class Failed(val message: IpFinderSearchFailure) : IpFinderSearchResult
    data object Cancelled : IpFinderSearchResult
}

enum class IpFinderSearchFailure {
    AIRPLANE_ALREADY_ON,
    THREE_CONSECUTIVE_NETWORK_FAILURES,
    AIRPLANE_RESTORE_FAILED,
    CYCLE_FAILED,
}

data class IpFinderSearchAttempt(
    val number: Int,
    val address: String?,
    val matchingRule: String?,
    val cycleFailure: IpFinderCycleFailure? = null,
)

class IpFinderSearchCoordinator(
    private val port: IpFinderSearchPort,
    private val rulesText: String,
    private val onAttempt: (IpFinderSearchAttempt) -> Unit = {},
    private val onCycleStarted: (Int) -> Unit = {},
    private val clock: IpFinderSearchClock = SystemIpFinderSearchClock,
    private val cycleTimeoutMillis: Long = CYCLE_TIMEOUT_MILLIS,
) {
    suspend fun run(cycleCount: Int, pauseSeconds: Int, firstAttempt: Int = 1): IpFinderSearchResult {
        if (port.isAirplaneModeEnabled()) return IpFinderSearchResult.Failed(IpFinderSearchFailure.AIRPLANE_ALREADY_ON)
        val parsed = IpRuleTextParser.parse(rulesText)
        if (parsed.inputError != null || parsed.validRules.isEmpty()) {
            return IpFinderSearchResult.Exhausted(0)
        }
        val validRuleText = parsed.validRules.joinToString(";")
        var consecutiveNetworkFailures = 0

        for (attempt in firstAttempt..cycleCount) {
            onCycleStarted(attempt)
            val cycleResult = try {
                clock.withTimeoutOrNull(cycleTimeoutMillis) { port.runCycle() }
                    ?: IpFinderCycleResult.Failure(IpFinderCycleFailure.CELLULAR_RETURN_TIMEOUT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            }

            when (cycleResult) {
                is IpFinderCycleResult.Success -> {
                    val addresses = port.currentCellularAddresses().filter(String::isNotBlank)
                    if (addresses.isEmpty()) {
                        consecutiveNetworkFailures++
                        onAttempt(IpFinderSearchAttempt(attempt, null, null, IpFinderCycleFailure.CELLULAR_RETURN_TIMEOUT))
                    } else {
                        consecutiveNetworkFailures = 0
                        val matchingAddress = addresses.firstNotNullOfOrNull { address ->
                            val match = IpRuleMatcher.match(validRuleText, address)
                            if (match.matches) address to match.triggeringRule else null
                        }
                        if (matchingAddress != null) {
                            val (address, rule) = matchingAddress
                            onAttempt(IpFinderSearchAttempt(attempt, address, rule))
                            return IpFinderSearchResult.Found(attempt, address, rule.orEmpty())
                        }
                        onAttempt(IpFinderSearchAttempt(attempt, addresses.joinToString(" · "), null))
                    }
                }
                is IpFinderCycleResult.Failure -> {
                    val networkFailure = cycleResult.reason in NETWORK_FAILURES
                    if (!networkFailure) return IpFinderSearchResult.Failed(IpFinderSearchFailure.CYCLE_FAILED)
                    consecutiveNetworkFailures++
                    onAttempt(IpFinderSearchAttempt(attempt, null, null, cycleResult.reason))
                    if (port.isAirplaneModeEnabled() && !port.restoreAirplaneMode()) {
                        return IpFinderSearchResult.Failed(IpFinderSearchFailure.AIRPLANE_RESTORE_FAILED)
                    }
                }
                IpFinderCycleResult.Cancelled -> throw CancellationException("IP Finder cycle cancelled")
            }

            if (consecutiveNetworkFailures >= MAX_CONSECUTIVE_FAILURES) {
                return IpFinderSearchResult.Failed(IpFinderSearchFailure.THREE_CONSECUTIVE_NETWORK_FAILURES)
            }
            if (attempt < cycleCount) clock.pause(pauseSeconds * 1_000L)
        }
        return IpFinderSearchResult.Exhausted(cycleCount)
    }

    companion object {
        const val CYCLE_TIMEOUT_MILLIS = 60_000L
        const val MAX_CONSECUTIVE_FAILURES = 3
        private val NETWORK_FAILURES = setOf(
            IpFinderCycleFailure.NO_INITIAL_CELLULAR_ADDRESS,
            IpFinderCycleFailure.CELLULAR_DID_NOT_DISCONNECT,
            IpFinderCycleFailure.CELLULAR_RETURN_TIMEOUT,
        )
    }
}
