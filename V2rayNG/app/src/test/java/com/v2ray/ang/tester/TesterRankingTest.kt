package com.v2ray.ang.tester

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TesterRankingTest {

    private fun row(
        name: String,
        tunnelDelay: Long = -1L,
        tunnelAt: Long = 0L,
        lossPercent: Int? = null,
    ) = TesterRow(
        guid = name,
        name = name,
        address = "127.0.0.1:443",
        scope = TesterScope.CONFIGS,
        measurement = TesterMeasurement(tunnelDelay = tunnelDelay, tunnelAt = tunnelAt),
        lossPercent = lossPercent,
    )

    @Test
    fun `never measured server ranks last`() {
        val measured = row("measured", tunnelDelay = 900L, tunnelAt = 1L)
        val never = row("never")

        assertTrue(measured.rank(TesterKind.TUNNEL) < never.rank(TesterKind.TUNNEL))
        assertEquals(Long.MAX_VALUE, never.rank(TesterKind.TUNNEL))
    }

    @Test
    fun `failed measurement ranks last and is not a valid delay`() {
        val failed = row("failed", tunnelDelay = -1L, tunnelAt = 1L)

        assertEquals(Long.MAX_VALUE, failed.rank(TesterKind.TUNNEL))
    }

    @Test
    fun `loss penalises an otherwise fast server`() {
        val fastLossy = row("lossy", tunnelDelay = 100L, tunnelAt = 1L, lossPercent = 50)
        val slowerStable = row("stable", tunnelDelay = 140L, tunnelAt = 1L, lossPercent = 0)

        assertTrue(slowerStable.rank(TesterKind.TUNNEL) < fastLossy.rank(TesterKind.TUNNEL))
    }

    @Test
    fun `kinds stay separated`() {
        val row = TesterRow(
            guid = "a",
            name = "a",
            address = "127.0.0.1:443",
            scope = TesterScope.FREE,
            measurement = TesterMeasurement(tunnelDelay = 400L, tunnelAt = 10L, tcpDelay = 30L, tcpAt = 20L),
            lossPercent = null,
        )

        assertEquals(400L, row.measurement.delayOf(TesterKind.TUNNEL))
        assertEquals(30L, row.measurement.delayOf(TesterKind.TCP))
        assertTrue(row.rank(TesterKind.TCP) < row.rank(TesterKind.TUNNEL))
    }
}
