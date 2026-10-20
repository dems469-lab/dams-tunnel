package com.v2ray.ang.core

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreLoopLifecycleGateTest {
    @Test
    fun immediateRestartWaitsUntilSlowStopCompletes() = runBlocking {
        val gate = CoreLoopLifecycleGate(stopTimeoutMillis = 500)
        val events = mutableListOf<String>()
        val stopping = async {
            gate.stop {
                events += "stop-start"
                delay(60)
                events += "stop-finished"
                true
            }
        }
        delay(10)
        val starting = async {
            gate.start {
                events += "start"
            }
        }

        assertTrue(stopping.await())
        starting.await()
        assertEquals(listOf("stop-start", "stop-finished", "start"), events)
    }

    @Test
    fun timeoutCancelsStopAndReturnsFailure() = runBlocking {
        val gate = CoreLoopLifecycleGate(stopTimeoutMillis = 20)
        var completed = false

        val stopped = gate.stop {
            delay(500)
            completed = true
            true
        }

        assertFalse(stopped)
        assertFalse(completed)
    }
}
