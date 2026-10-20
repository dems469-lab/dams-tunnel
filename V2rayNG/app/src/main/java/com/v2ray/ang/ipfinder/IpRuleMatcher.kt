package com.v2ray.ang.ipfinder

/** Pure Kotlin parser and matcher for IP Finder rules. This class has no Android dependencies. */
object IpRuleMatcher {
    const val MAX_RULES = 1_000
    const val MAX_TEXT_BYTES = 20 * 1024

    /** Parses [rulesText] and returns the first matching rule for [ipAddress], if any. */
    fun match(rulesText: String, ipAddress: String): IpRuleResult {
        if (rulesText.length > MAX_TEXT_BYTES || rulesText.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
            return IpRuleResult(inputError = IpRuleInputError.TEXT_TOO_LARGE)
        }

        val parsedRules = mutableListOf<ParsedRule>()
        val invalidRules = mutableListOf<InvalidIpRule>()
        val seenRules = mutableSetOf<String>()
        var nonEmptyRuleCount = 0
        var lineNumber = 1
        var tokenLineNumber = 1
        val token = StringBuilder()

        fun consumeToken() {
            val source = token.toString().trim()
            token.setLength(0)
            if (source.isEmpty()) return

            nonEmptyRuleCount++
            if (nonEmptyRuleCount > MAX_RULES) return

            val rule = parseRule(source)
            if (rule == null) {
                invalidRules += InvalidIpRule(tokenLineNumber, source, InvalidIpRuleReason.EMPTY_PATTERN)
            } else if (seenRules.add(source)) {
                parsedRules += rule
            }
        }

        var index = 0
        while (index < rulesText.length) {
            when (val char = rulesText[index]) {
                ',', ';' -> consumeToken()
                '\n', '\r' -> {
                    consumeToken()
                    if (char == '\r' && index + 1 < rulesText.length && rulesText[index + 1] == '\n') {
                        index++
                    }
                    lineNumber++
                    tokenLineNumber = lineNumber
                }
                else -> token.append(char)
            }
            if (nonEmptyRuleCount > MAX_RULES) {
                return IpRuleResult(inputError = IpRuleInputError.TOO_MANY_RULES)
            }
            index++
        }
        consumeToken()
        if (nonEmptyRuleCount > MAX_RULES) {
            return IpRuleResult(inputError = IpRuleInputError.TOO_MANY_RULES)
        }

        val ipv6 = ':' in ipAddress
        val matchingRule = parsedRules.firstOrNull { it.matches(ipAddress, ipv6) }
        return IpRuleResult(
            matches = matchingRule != null,
            triggeringRule = matchingRule?.source,
            invalidRules = invalidRules,
            activeRuleCount = parsedRules.size,
        )
    }

    private fun parseRule(source: String): ParsedRule? {
        val operator: IpRuleOperator
        val pattern: String
        when {
            source.startsWith('^') -> {
                operator = IpRuleOperator.PREFIX
                pattern = source.substring(1)
            }
            source.startsWith('=') -> {
                operator = IpRuleOperator.EXACT
                pattern = source.substring(1)
            }
            source.endsWith('$') -> {
                operator = IpRuleOperator.SUFFIX
                pattern = source.dropLast(1)
            }
            else -> {
                operator = IpRuleOperator.CONTAINS
                pattern = source
            }
        }
        if (pattern.isEmpty()) return null
        return ParsedRule(source, operator, pattern)
    }

    private data class ParsedRule(
        val source: String,
        val operator: IpRuleOperator,
        val pattern: String,
    ) {
        fun matches(address: String, ignoreCase: Boolean): Boolean = when (operator) {
            IpRuleOperator.PREFIX -> address.regionMatches(0, pattern, 0, pattern.length, ignoreCase)
            IpRuleOperator.SUFFIX -> address.length >= pattern.length &&
                address.regionMatches(address.length - pattern.length, pattern, 0, pattern.length, ignoreCase)
            IpRuleOperator.EXACT -> address.equals(pattern, ignoreCase)
            IpRuleOperator.CONTAINS -> address.containsLiteral(pattern, ignoreCase)
        }
    }

    private fun String.containsLiteral(pattern: String, ignoreCase: Boolean): Boolean {
        if (pattern.length > length) return false
        for (start in 0..length - pattern.length) {
            if (regionMatches(start, pattern, 0, pattern.length, ignoreCase)) return true
        }
        return false
    }
}

enum class IpRuleOperator { PREFIX, SUFFIX, EXACT, CONTAINS }

data class InvalidIpRule(
    val lineNumber: Int,
    val source: String,
    val reason: InvalidIpRuleReason,
)

enum class InvalidIpRuleReason { EMPTY_PATTERN }

enum class IpRuleInputError { TEXT_TOO_LARGE, TOO_MANY_RULES }

data class IpRuleResult(
    val matches: Boolean = false,
    val triggeringRule: String? = null,
    val invalidRules: List<InvalidIpRule> = emptyList(),
    val activeRuleCount: Int = 0,
    val inputError: IpRuleInputError? = null,
)
