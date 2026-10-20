package com.v2ray.ang.ipfinder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IpFinderCycleCoordinatorTest {
    @Test
    fun completesOneCycleAndClearsRecoveryAfterAirplaneOffIsConfirmed() = runBlocking {
        val port = FakePort()
        val flag = FakeFlag()
        val result = coordinator(port, flag).runSingleCycle()
        assertEquals(IpFinderCycleResult.Success("100.64.1.2", "100.64.1.3"), result)
        assertEquals(listOf(true, false), port.requestedModes.take(2))
        assertFalse(flag.pending)
    }

    @Test
    fun recoveryFlagIsPersistedBeforeFirstAirplaneRequest() = runBlocking {
        val flag = FakeFlag()
        val port = FakePort().apply { beforeRequest = { assertTrue(flag.pending) } }
        assertTrue(coordinator(port, flag).runSingleCycle() is IpFinderCycleResult.Success)
        assertEquals(2, port.requestedModes.size)
    }

    @Test
    fun rejectedSystemRequestFailsAndAttemptsRestoration() = runBlocking {
        val port = FakePort().apply { requestResults += IpFinderAirplaneRequest(false, false) }
        val flag = FakeFlag()
        val result = coordinator(port, flag).runSingleCycle()
        assertEquals(
            IpFinderCycleResult.Failure(IpFinderCycleFailure.ASSISTANT_REQUEST_REJECTED),
            result
        )
        assertEquals(listOf(true, false), port.requestedModes)
        assertFalse(flag.pending)
    }

    @Test
    fun cellularReturnTimeoutFailsAfterAirplaneWasRestored() = runBlocking {
        val port = FakePort().apply { returnedAddress = null }
        val flag = FakeFlag()
        val result = coordinator(port, flag).runSingleCycle()
        assertEquals(
            IpFinderCycleResult.Failure(IpFinderCycleFailure.CELLULAR_RETURN_TIMEOUT),
            result
        )
        assertFalse(flag.pending)
    }

    @Test
    fun foregroundReturnTimeoutReportsFailureAndMakesRestoreAttempt() = runBlocking {
        val port = FakePort().apply {
            requestResults += IpFinderAirplaneRequest(true, true)
            requestResults += IpFinderAirplaneRequest(false, false, foregroundTimeout = true)
        }
        val flag = FakeFlag()
        val result = coordinator(port, flag).runSingleCycle()
        assertEquals(
            IpFinderCycleResult.Failure(IpFinderCycleFailure.FOREGROUND_RETURN_TIMEOUT),
            result
        )
        assertEquals(listOf(true, false, false), port.requestedModes)
        assertFalse(flag.pending)
    }

    @Test
    fun cancellationDuringNetworkWaitRunsNonCancellableRestoration() {
        val port = FakePort().apply { cancelOnCellularDisconnect = true }
        val flag = FakeFlag()
        try {
            runBlocking { coordinator(port, flag).runSingleCycle() }
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(listOf(true, false), port.requestedModes)
            assertFalse(flag.pending)
        }
    }

    @Test
    fun rejectedSecondToggleReturnsFailureAndRestoresByRetryingOff() = runBlocking {
        val port = FakePort().apply {
            requestResults += IpFinderAirplaneRequest(true, true)
            requestResults += IpFinderAirplaneRequest(false, false)
        }
        val flag = FakeFlag()
        val result = coordinator(port, flag).runSingleCycle()
        assertEquals(
            IpFinderCycleResult.Failure(IpFinderCycleFailure.ASSISTANT_REQUEST_REJECTED),
            result
        )
        assertEquals(listOf(true, false, false), port.requestedModes)
        assertFalse(flag.pending)
    }

    @Test
    fun recoveryFlagRemainsWhenAirplaneOffRestoreIsRejected() = runBlocking {
        val port = FakePort().apply {
            requestResults += IpFinderAirplaneRequest(false, false)
            requestResults += IpFinderAirplaneRequest(false, false)
        }
        val flag = FakeFlag()
        val events = mutableListOf<IpFinderCycleEvent>()
        val result = coordinator(port, flag, events).runSingleCycle()
        assertEquals(
            IpFinderCycleResult.Failure(IpFinderCycleFailure.ASSISTANT_REQUEST_REJECTED),
            result
        )
        assertTrue(flag.pending)
        assertTrue(events.contains(IpFinderCycleEvent.RESTORE_FAILED))
    }

    @Test
    fun recoveryFlagRemainsWhenOffStateCannotBeConfirmed() = runBlocking {
        val port = FakePort().apply {
            airplaneOffConfirmed = false
            requestResults += IpFinderAirplaneRequest(false, false)
        }
        val flag = FakeFlag()
        coordinator(port, flag).runSingleCycle()
        assertTrue(flag.pending)
    }

    private fun coordinator(
        port: FakePort,
        flag: FakeFlag,
        events: MutableList<IpFinderCycleEvent> = mutableListOf()
    ) = IpFinderCycleCoordinator(port, flag, events::add, timeoutMillis = 5)

    private class FakeFlag : IpFinderRecoveryFlag {
        var pending = false
        override fun isPending() = pending
        override fun markPending(): Boolean {
            pending = true
            return true
        }
        override fun clear() { pending = false }
    }

    private class FakePort : IpFinderCyclePort {
        val requestedModes = mutableListOf<Boolean>()
        val requestResults = ArrayDeque<IpFinderAirplaneRequest>()
        var beforeRequest: (() -> Unit)? = null
        var cancelOnCellularDisconnect = false
        var airplaneOffConfirmed = true
        var returnedAddress: String? = "100.64.1.3"

        override fun isWifiConnected() = false
        override fun currentCellularAddress() = "100.64.1.2"

        override suspend fun requestAirplaneMode(enabled: Boolean): IpFinderAirplaneRequest {
            beforeRequest?.invoke()
            requestedModes += enabled
            return requestResults.removeFirstOrNull() ?: IpFinderAirplaneRequest(true, true)
        }

        override suspend fun awaitAirplaneMode(enabled: Boolean, timeoutMillis: Long) =
            enabled || airplaneOffConfirmed

        override suspend fun awaitCellularDisconnect(timeoutMillis: Long): Boolean {
            if (cancelOnCellularDisconnect) throw CancellationException("test cancellation")
            return true
        }

        override suspend fun awaitCellularAddress(timeoutMillis: Long) = returnedAddress
    }
}
