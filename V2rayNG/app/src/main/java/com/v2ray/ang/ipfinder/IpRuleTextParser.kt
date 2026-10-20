package com.v2ray.ang.ipfinder

/** Validates user fragments without rewriting the text they entered. */
object IpRuleTextParser {
    fun parse(text: String): IpRuleTextParseResult {
        if (text.length > IpRuleMatcher.MAX_TEXT_BYTES ||
            text.toByteArray(Charsets.UTF_8).size > IpRuleMatcher.MAX_TEXT_BYTES
        ) {
            return IpRuleTextParseResult(inputError = IpRuleInputError.TEXT_TOO_LARGE)
        }

        val valid = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val ignored = mutableListOf<IpFinderIgnoredRule>()
        var nonEmptyCount = 0
        var line = 1
        var tokenLine = 1
        val token = StringBuilder()

        fun consume() {
            val source = token.toString().trim()
            token.setLength(0)
            if (source.isEmpty()) return
            nonEmptyCount++
            if (nonEmptyCount > IpRuleMatcher.MAX_RULES) return
            if (isValid(source)) {
                if (seen.add(source)) valid += source
            } else {
                ignored += IpFinderIgnoredRule(source, tokenLine)
            }
        }

        var index = 0
        while (index < text.length) {
            when (val char = text[index]) {
                ',', ';' -> consume()
                '\n', '\r' -> {
                    consume()
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    line++
                    tokenLine = line
                }
                else -> token.append(char)
            }
            if (nonEmptyCount > IpRuleMatcher.MAX_RULES) {
                return IpRuleTextParseResult(inputError = IpRuleInputError.TOO_MANY_RULES)
            }
            index++
        }
        consume()
        if (nonEmptyCount > IpRuleMatcher.MAX_RULES) {
            return IpRuleTextParseResult(inputError = IpRuleInputError.TOO_MANY_RULES)
        }
        return IpRuleTextParseResult(valid, ignored)
    }

    /** Adds ^ only to valid unmarked fragments, preserving invalid fragments and separators. */
    fun prefixValidUnmarked(text: String): String {
        if (parse(text).inputError != null) return text
        val output = StringBuilder(text.length + 16)
        var tokenStart = 0
        var index = 0
        while (index <= text.length) {
            if (index == text.length || text[index] in charArrayOf(',', ';', '\n', '\r')) {
                val end = index
                var contentStart = tokenStart
                var contentEnd = end
                while (contentStart < contentEnd && text[contentStart].isWhitespace()) contentStart++
                while (contentEnd > contentStart && text[contentEnd - 1].isWhitespace()) contentEnd--
                output.append(text, tokenStart, contentStart)
                val source = text.substring(contentStart, contentEnd)
                if (source.isNotEmpty() && isValid(source) &&
                    !source.startsWith('^') && !source.startsWith('=') && !source.endsWith('$')
                ) output.append('^')
                output.append(source)
                output.append(text, contentEnd, end)
                if (index < text.length) output.append(text[index])
                tokenStart = index + 1
            }
            index++
        }
        return output.toString()
    }

    private fun isValid(source: String): Boolean {
        val mode: Char?
        val body: String
        when {
            source.startsWith('^') -> { mode = '^'; body = source.substring(1) }
            source.startsWith('=') -> { mode = '='; body = source.substring(1) }
            source.endsWith('$') -> { mode = '$'; body = source.dropLast(1) }
            else -> { mode = null; body = source }
        }
        if (body.isEmpty() || body.any { it == '^' || it == '=' || it == '$' }) return false

        val groups = mutableListOf<String>()
        var groupStart = 0
        for (position in body.indices) {
            if (body[position] == '.') {
                if (position == groupStart) return false
                groups += body.substring(groupStart, position)
                groupStart = position + 1
            }
        }
        if (groupStart == body.length) return false
        groups += body.substring(groupStart)
        if (groups.size !in 1..4) return false
        for (group in groups) {
            if (group.length !in 1..3 || group.any { it !in '0'..'9' }) return false
            val octet = group.toIntOrNull() ?: return false
            if (octet !in 0..255) return false
        }

        return when (mode) {
            '$' -> true
            '=' -> groups.size == 4 && groups.first() == "10"
            '^', null -> groups.size >= 2 && groups.first() == "10"
            else -> false
        }
    }
}

data class IpRuleTextParseResult(
    val validRules: List<String> = emptyList(),
    val ignoredRules: List<IpFinderIgnoredRule> = emptyList(),
    val inputError: IpRuleInputError? = null,
)

data class IpFinderIgnoredRule(val source: String, val lineNumber: Int)
