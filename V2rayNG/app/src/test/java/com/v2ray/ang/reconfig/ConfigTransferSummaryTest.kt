package com.v2ray.ang.reconfig.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigTransferSummaryTest {

    @Test
    fun `only newly added rows count as transferred`() {
        val summary = ConfigTransferSummary(
            added = 2,
            alreadyPresent = 3,
            invalid = 1,
            failed = 4,
        )

        assertEquals(2, summary.transferred)
    }

    @Test
    fun `duplicates are kept and never scheduled for removal`() {
        val summary = ConfigTransferSummary(
            added = 1,
            alreadyPresent = 1,
            removableIds = listOf(10L),
            duplicateIds = listOf(20L),
        )

        assertTrue(10L in summary.removableIds)
        assertTrue(20L !in summary.removableIds)
        assertTrue(20L in summary.duplicateIds)
    }
}
