package com.v2ray.ang.ipfinder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IpFinderLoopSettingsTest {
    @Test
    fun firstLoadPersistsDefaultsAndSubsequentLoadRestoresUserValues() {
        val storage = FakeStorage()
        val repository = IpFinderLoopSettingsRepository(storage)
        assertEquals(IpFinderLoopSettings("10", "5"), repository.load())
        assertEquals("10", storage.values[IpFinderLoopSettingsRepository.CYCLES_KEY])
        assertEquals("5", storage.values[IpFinderLoopSettingsRepository.PAUSE_KEY])

        repository.saveCycles("1000")
        repository.savePause("120")
        assertEquals(IpFinderLoopSettings("1000", "120"), IpFinderLoopSettingsRepository(storage).load())
    }

    @Test
    fun validatesInclusiveCycleAndPauseRangesWithoutCoercion() {
        assertEquals(1, IpFinderLoopNumberValidator.cycles("1").value)
        assertEquals(1_000, IpFinderLoopNumberValidator.cycles("1000").value)
        assertEquals(3, IpFinderLoopNumberValidator.pauseSeconds("3").value)
        assertEquals(120, IpFinderLoopNumberValidator.pauseSeconds("120").value)

        listOf("0", "1001", "", "abc", "5.5").forEach {
            assertFalse(IpFinderLoopNumberValidator.cycles(it).isValid, "cycles=$it")
        }
        listOf("2", "121", "", "abc", "5.5").forEach {
            assertFalse(IpFinderLoopNumberValidator.pauseSeconds(it).isValid, "pause=$it")
        }
        assertTrue(IpFinderLoopNumberValidator.cycles("1").isValid)
    }

    @Test
    fun interruptedSearchMarkerIsRetainedUntilAirplaneRestoreIsConfirmed() {
        assertFalse(IpFinderSearchRecoveryPolicy.canClearLoopMarker(true, true))
        assertFalse(IpFinderSearchRecoveryPolicy.canClearLoopMarker(true, false))
        assertFalse(IpFinderSearchRecoveryPolicy.canClearLoopMarker(false, true))
        assertTrue(IpFinderSearchRecoveryPolicy.canClearLoopMarker(false, false))
    }

    private class FakeStorage : IpFinderLoopSettingsStorage {
        val values = mutableMapOf<String, String>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String) { values[key] = value }
    }
}
