package com.v2ray.ang.ipfinder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IpRuleMatcherTest {
    @Test
    fun prefixMatchesOnlyAtTheBeginning() {
        assertTrue(IpRuleMatcher.match("^10.76", "10.76.1.1").matches)
        assertFalse(IpRuleMatcher.match("^10.76", "110.76.1.1").matches)
    }

    @Test
    fun suffixMatchesOnlyAtTheEnd() {
        assertTrue(IpRuleMatcher.match("1.1$", "10.76.1.1").matches)
        assertFalse(IpRuleMatcher.match("1.1$", "10.76.1.10").matches)
    }

    @Test
    fun exactRuleRequiresWholeAddress() {
        assertTrue(IpRuleMatcher.match("=10.76.1.1", "10.76.1.1").matches)
        assertFalse(IpRuleMatcher.match("=10.76.1.1", "10.76.1.10").matches)
    }

    @Test
    fun unmarkedRuleUsesCompleteIpv4GroupsAndPeriodsAreNotWildcards() {
        assertTrue(IpRuleMatcher.match("10.76", "10.76.1.1").matches)
        assertFalse(IpRuleMatcher.match("10.76", "110.76.1.1").matches)
        assertFalse(IpRuleMatcher.match("10.76", "10.176.1.1").matches)
        assertFalse(IpRuleMatcher.match("10.76", "10x76.1.1").matches)
    }

    @Test
    fun anIpv4RuleNeverMatchesAFragmentOfAnotherOctet() {
        assertFalse(IpRuleMatcher.match("10.10", "10.100.4.5").matches)
        assertFalse(IpRuleMatcher.match("10.165", "10.188.210.165").matches)
        assertTrue(IpRuleMatcher.match("10.188", "10.188.210.165").matches)
    }

    @Test
    fun acceptsSeparatorsCombinationsWhitespaceAndBlankLines() {
        val result = IpRuleMatcher.match("  ^10.76 ;\n\n=10.1.2.3, 10.4\r\n", "10.76.1.1")
        assertTrue(result.matches)
        assertEquals(3, result.activeRuleCount)
    }

    @Test
    fun trimsAndDeduplicatesRulesPreservingFirstMatchOrder() {
        val result = IpRuleMatcher.match(" 10.7 ;10.7, ^10.7\n", "10.7.1.1")
        assertEquals(2, result.activeRuleCount)
        assertEquals("10.7", result.triggeringRule)
    }

    @Test
    fun emptyInputAndOnlyWhitespaceHaveNoRules() {
        for (text in listOf("", " \n ; , \t")) {
            val result = IpRuleMatcher.match(text, "10.1.2.3")
            assertFalse(result.matches)
            assertEquals(0, result.activeRuleCount)
            assertNull(result.inputError)
        }
    }

    @Test
    fun invalidEmptyPatternsAreReportedBySourceLineAndIgnored() {
        val result = IpRuleMatcher.match("^;\n=, ^10.76", "10.76.1.1")
        assertTrue(result.matches)
        assertEquals(1, result.activeRuleCount)
        assertEquals(listOf(1, 2), result.invalidRules.map { it.lineNumber })
    }

    @Test
    fun ipv6MatchingIgnoresLetterCase() {
        assertTrue(IpRuleMatcher.match("^2001:DB8:AB", "2001:db8:ab::1").matches)
        assertTrue(IpRuleMatcher.match("=2001:DB8::1", "2001:db8::1").matches)
        assertFalse(IpRuleMatcher.match("=2001:DB8::2", "2001:db8::1").matches)
    }

    @Test
    fun oneThousandRulesAreAcceptedAndOneThousandOneAreRejected() {
        val accepted = (1..IpRuleMatcher.MAX_RULES).joinToString(",") { "=$it" }
        val rejected = "$accepted,=1001"
        assertEquals(IpRuleMatcher.MAX_RULES, IpRuleMatcher.match(accepted, "no-match").activeRuleCount)
        assertEquals(IpRuleInputError.TOO_MANY_RULES, IpRuleMatcher.match(rejected, "no-match").inputError)
    }

    @Test
    fun exactlyTwentyKiBIsAcceptedAndLargerTextIsRejected() {
        val accepted = "x".repeat(IpRuleMatcher.MAX_TEXT_BYTES)
        val rejected = "x".repeat(IpRuleMatcher.MAX_TEXT_BYTES + 1)
        assertNull(IpRuleMatcher.match(accepted, "no-match").inputError)
        assertEquals(IpRuleInputError.TEXT_TOO_LARGE, IpRuleMatcher.match(rejected, "no-match").inputError)
        assertEquals(IpRuleInputError.TEXT_TOO_LARGE, IpRuleMatcher.match("é".repeat(10_241), "no-match").inputError)
    }

    @Test
    fun captureRulesProduceEighteenUniqueRulesAndExpectedMatches() {
        val capture = "10.161;10.76;10.74;10.102;10.46;10.75;10.102;10.195;10.196;10.197;10.198;10.199;10.204;10.205;10.206;10.207;10.208;10.209;10.210"
        val positive = IpRuleMatcher.match(capture, "10.76.1.1")
        assertTrue(positive.matches)
        assertEquals(18, positive.activeRuleCount)
        assertFalse(IpRuleMatcher.match(capture, "10.203.138.64").matches)
        assertFalse(IpRuleMatcher.match(capture, "10.148.4.131").matches)
    }
}
