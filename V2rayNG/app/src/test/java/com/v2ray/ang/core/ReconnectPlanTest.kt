package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReconnectPlanTest {
    @Test
    fun `reconnect tears the previous tunnel down before resolving and restarting`() {
        val plan = planReconnect(startInProgress = false, tunnelRunning = true)

        assertEquals(
            listOf(
                ReconnectStep.STOP_PREVIOUS_TUNNEL,
                ReconnectStep.RESOLVE_DNS,
                ReconnectStep.START_CORE,
            ),
            plan?.steps,
        )
    }

    @Test
    fun `no restart while a start is already in progress`() {
        assertNull(planReconnect(startInProgress = true, tunnelRunning = true))
        assertNull(planReconnect(startInProgress = true, tunnelRunning = false))
    }

    @Test
    fun `a stopped tunnel is not torn down twice`() {
        assertEquals(
            listOf(ReconnectStep.RESOLVE_DNS, ReconnectStep.START_CORE),
            planReconnect(startInProgress = false, tunnelRunning = false)?.steps,
        )
    }
}
