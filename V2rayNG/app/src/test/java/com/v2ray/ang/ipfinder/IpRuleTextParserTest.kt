package com.v2ray.ang.ipfinder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IpRuleTextParserTest {
    @Test
    fun ignoresInvalidFragmentsAndKeepsValidRulesInOriginalOrder() {
        val result = IpRuleTextParser.parse("0.63;10.165;10.67")
        assertEquals(listOf("10.165", "10.67"), result.validRules)
        assertEquals(listOf(IpFinderIgnoredRule("0.63", 1)), result.ignoredRules)
        assertNull(result.inputError)
    }

    @Test
    fun acceptsSeparatorsWhitespaceAndIgnoresEmptyFragments() {
        assertEquals(listOf("10.63", "10.165"), IpRuleTextParser.parse("10.63;;10.165;").validRules)
        assertEquals(listOf("10.63", "10.165"), IpRuleTextParser.parse("10.63 ; 10.165").validRules)
        assertEquals(emptyList<String>(), IpRuleTextParser.parse("10,63").validRules)
        assertEquals(listOf("10", "63"), IpRuleTextParser.parse("10,63").ignoredRules.map { it.source })
    }

    @Test
    fun validatesGroupsSymbolsAndOctetRange() {
        val cases = mapOf(
            "10.6a" to false,
            "10..63" to false,
            "10.300" to false,
            "10.63.1.1.1" to false,
            "10" to false,
            "^10.63" to true,
            "^" to false,
            "=10.63.1.1" to true,
            "=10.63" to false,
            "63$" to true,
            "10.63" to true,
        )
        cases.forEach { (rule, valid) ->
            assertEquals(valid, IpRuleTextParser.parse(rule).validRules.isNotEmpty(), rule)
        }
        assertEquals(listOf("10.165"), IpRuleTextParser.parse("10.6a;10.165").validRules)
    }

    @Test
    fun preservesSourceTextAndPrefixesOnlyValidUnmarkedFragments() {
        val input = "10.63;0.63;^10.65;=10.67.1.1;10.300;10.68$"
        assertEquals("^10.63;0.63;^10.65;=10.67.1.1;10.300;10.68$", IpRuleTextParser.prefixValidUnmarked(input))
        val parsed = IpRuleTextParser.parse(input)
        assertEquals(listOf("10.63", "^10.65", "=10.67.1.1", "10.68$"), parsed.validRules)
        assertEquals(listOf("0.63", "10.300"), parsed.ignoredRules.map { it.source })
    }

    @Test
    fun reportsLineNumbersAndEnforcesInputLimits() {
        val parsed = IpRuleTextParser.parse("0.63;10.165\n10..67,10.68")
        assertEquals(listOf(IpFinderIgnoredRule("0.63", 1), IpFinderIgnoredRule("10..67", 2)), parsed.ignoredRules)
        assertEquals(IpRuleInputError.TOO_MANY_RULES, IpRuleTextParser.parse(List(1_001) { "10.1" }.joinToString(";")).inputError)
        assertEquals(IpRuleInputError.TEXT_TOO_LARGE, IpRuleTextParser.parse("x".repeat(20 * 1024 + 1)).inputError)
    }

    @Test
    fun leavesDefaultListUntouchedAndKeepsUniqueRuleCount() {
        val result = IpRuleTextParser.parse(IpFinderRulesRepository.DEFAULT_RULES)
        assertEquals(33, IpFinderRulesRepository.DEFAULT_RULES.split(';').size)
        assertEquals(32, result.validRules.size)
        assertTrue(result.ignoredRules.isEmpty())
        assertFalse(result.validRules.contains("^10.204"))
    }
}
