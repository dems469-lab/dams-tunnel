package com.v2ray.ang.ipfinder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IpFinderRulesRepositoryTest {
    @Test
    fun firstOpenStoresTheDefaultAndSubsequentOpenKeepsUserText() {
        val storage = FakeIpRuleTextStorage()
        val repository = IpFinderRulesRepository(storage)

        assertEquals(IpFinderRulesRepository.DEFAULT_RULES, repository.load())
        assertEquals(IpFinderRulesRepository.DEFAULT_RULES, storage.value)
        repository.save("^10.1;=2001:db8::1")
        assertEquals("^10.1;=2001:db8::1", IpFinderRulesRepository(storage).load())
    }

    @Test
    fun resetRestoresAndPersistsTheExactDefault() {
        val storage = FakeIpRuleTextStorage("custom")
        assertEquals(IpFinderRulesRepository.DEFAULT_RULES, IpFinderRulesRepository(storage).reset())
        assertEquals(IpFinderRulesRepository.DEFAULT_RULES, storage.value)
    }

    @Test
    fun defaultListHasThirtyThreeEntriesAndThirtyTwoUniqueActiveRules() {
        val defaultRules = IpFinderRulesRepository.DEFAULT_RULES
        val parsed = IpRuleMatcher.match(defaultRules, "no-match")

        assertEquals(33, defaultRules.split(';').size)
        assertEquals(32, parsed.activeRuleCount)
    }

    @Test
    fun prefixAddsOnlyMissingPrefixAndDoesNotDuplicateExistingMarkers() {
        val storage = FakeIpRuleTextStorage()
        val repository = IpFinderRulesRepository(storage)

        assertEquals("^10.1; ^10.2;=10.3;^10.4;10.5$", repository.prefixAndSave("10.1; 10.2;=10.3;^10.4;10.5$"))
        assertEquals("^10.1; ^10.2;=10.3;^10.4;10.5$", storage.value)
        assertEquals(storage.value, repository.prefixUnmarkedRules(storage.value.orEmpty()))
    }

    private class FakeIpRuleTextStorage(var value: String? = null) : IpRuleTextStorage {
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }
}
