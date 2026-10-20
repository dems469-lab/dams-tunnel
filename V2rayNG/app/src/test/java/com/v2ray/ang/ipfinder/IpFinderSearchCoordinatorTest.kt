package com.v2ray.ang.ipfinder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IpFinderSearchCoordinatorTest {
    @Test
    fun stopsOnThirdAttemptWhenRuleMatches() = runBlocking {
        val port = FakeSearchPort(addresses = listOf("10.62.1.1", "10.61.1.1", "10.63.1.1"))
        val attempts = mutableListOf<IpFinderSearchAttempt>()
        val clock = FakeClock()
        val result = coordinator(port, attempts::add, clock).run(10, 3)
        assertEquals(IpFinderSearchResult.Found(3, "10.63.1.1", "10.63"), result)
        assertEquals(listOf(1, 2, 3), attempts.map { it.number })
        assertEquals(2, clock.pauseCalls)
    }

    @Test
    fun matchesAnyCellularAddressInsteadOfOnlyTheFirst() = runBlocking {
        val port = FakeSearchPort(addresses = listOf("10.61.1.1"))
        port.currentAddresses = listOf("2001:db8::1", "10.63.4.5")

        assertEquals(
            IpFinderSearchResult.Found(1, "10.63.4.5", "10.63"),
            coordinator(port).run(1, 3)
        )
    }

    @Test
    fun stopsAfterConfiguredAttemptsWithoutMatch() = runBlocking {
        val port = FakeSearchPort(addresses = listOf("10.61.1.1", "10.62.1.1", "10.64.1.1"))
        val clock = FakeClock()
        val result = coordinator(port, clock = clock).run(3, 5)
        assertEquals(IpFinderSearchResult.Exhausted(3), result)
        assertEquals(3, port.cycleCalls)
        assertEquals(2, clock.pauseCalls)
    }

    @Test
    fun stopsAfterThreeConsecutiveCellularFailures() = runBlocking {
        val failure = IpFinderCycleResult.Failure(IpFinderCycleFailure.CELLULAR_RETURN_TIMEOUT)
        val port = FakeSearchPort(results = listOf(failure, failure, failure))
        val result = coordinator(port).run(20, 3)
        assertEquals(IpFinderSearchResult.Failed(IpFinderSearchFailure.THREE_CONSECUTIVE_NETWORK_FAILURES), result)
        assertEquals(3, port.cycleCalls)
    }

    @Test
    fun refusesWhenAirplaneModeIsAlreadyOn() = runBlocking {
        val port = FakeSearchPort(airplaneOn = true)
        assertEquals(
            IpFinderSearchResult.Failed(IpFinderSearchFailure.AIRPLANE_ALREADY_ON),
            coordinator(port).run(1, 3)
        )
        assertEquals(0, port.cycleCalls)
    }

    @Test
    fun enforcesMaximumCycleDuration() = runBlocking {
        val port = FakeSearchPort()
        val clock = FakeClock(forceTimeout = true)
        val result = coordinator(port, clock = clock).run(3, 3)
        assertEquals(IpFinderSearchResult.Failed(IpFinderSearchFailure.THREE_CONSECUTIVE_NETWORK_FAILURES), result)
        assertEquals(3, clock.timeoutCalls)
    }

    @Test
    fun cancellationDuringCycleRestoresAirplaneMode() = runBlocking {
        val port = FakeSearchPort(blockInCycle = true)
        val job: Job = launch { coordinator(port).run(5, 3) }
        delay(10)
        job.cancelAndJoin()
        assertTrue(port.restored)
        assertFalse(port.airplaneOn)
    }

    private fun coordinator(
        port: FakeSearchPort,
        onAttempt: (IpFinderSearchAttempt) -> Unit = {},
        clock: IpFinderSearchClock = SystemIpFinderSearchClock,
        timeoutMillis: Long = 60_000,
    ) = IpFinderSearchCoordinator(port, "10.63", onAttempt, clock = clock, cycleTimeoutMillis = timeoutMillis)

    private class FakeSearchPort(
        private val results: List<IpFinderCycleResult> = emptyList(),
        addresses: List<String?> = emptyList(),
        var airplaneOn: Boolean = false,
        private val blockInCycle: Boolean = false,
    ) : IpFinderSearchPort {
        private val addressQueue = ArrayDeque(addresses)
        private var currentAddress: String? = null
        var cycleCalls = 0
        var restored = false
        var currentAddresses: List<String>? = null

        override fun isAirplaneModeEnabled() = airplaneOn

        override suspend fun runCycle(): IpFinderCycleResult {
            cycleCalls++
            if (blockInCycle) {
                airplaneOn = true
                try {
                    awaitCancellation()
                } finally {
                    airplaneOn = false
                    restored = true
                }
            }
            val result = results.getOrNull(cycleCalls - 1) ?: IpFinderCycleResult.Success("10.1.1.1", "10.1.1.2")
            currentAddress = when (result) {
                is IpFinderCycleResult.Success -> addressQueue.removeFirstOrNull() ?: result.addressAfter
                is IpFinderCycleResult.Failure -> null
                IpFinderCycleResult.Cancelled -> null
            }
            return result
        }

        override fun currentCellularAddress(): String? = currentAddress
        override fun currentCellularAddresses(): List<String> = currentAddresses ?: listOfNotNull(currentAddress)

        override suspend fun restoreAirplaneMode(): Boolean {
            airplaneOn = false
            restored = true
            return true
        }

    }

    private class FakeClock(private val forceTimeout: Boolean = false) : IpFinderSearchClock {
        var pauseCalls = 0
        var timeoutCalls = 0
        override suspend fun <T> withTimeoutOrNull(timeoutMillis: Long, block: suspend () -> T): T? {
            timeoutCalls++
            return if (forceTimeout) null else block()
        }
        override suspend fun pause(milliseconds: Long) { pauseCalls++ }
    }
}
